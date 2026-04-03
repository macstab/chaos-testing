/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu.command;

import java.util.Objects;

import com.macstab.chaos.core.command.cpu.CpuCommandBuilder;
import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * {@link CpuCommandBuilder} implementation using {@code stress-ng}, {@code cpulimit},
 * {@code taskset}, and {@code renice}.
 *
 * <p>All commands work in any unprivileged Linux container without additional capabilities or
 * kernel modules. No cgroup writes, no {@code --privileged}, no {@code CAP_SYS_ADMIN}.
 *
 * <p><strong>Required tools (auto-installed by {@link
 * com.macstab.chaos.core.util.PackageInstaller}):</strong>
 *
 * <ul>
 *   <li>{@code stress-ng} - {@code apt install stress-ng}
 *   <li>{@code cpulimit} - {@code apt install cpulimit}
 *   <li>{@code taskset} - {@code apt install util-linux} (already in most base images)
 *   <li>{@code renice} - part of {@code util-linux} or {@code bsdutils} (always present)
 * </ul>
 *
 * <p><strong>Process lifecycle strategy:</strong>
 *
 * <p>All process find/kill commands use /proc/[0-9]&#42;/comm exclusively - no dependency on
 * pgrep, pkill, or ps, which may be absent in minimal images (e.g., redis:7.4).
 *
 * <p><strong>stress-ng SIGTERM handling note:</strong>
 *
 * <p>stress-ng catches SIGTERM on the parent and cooperatively terminates all worker children.
 * Worker processes (renamed to stress-ng-cpu, stress-ng-cache, etc.) must NOT receive SIGKILL
 * directly - the parent re-spawns them. Always signal the parent PID with SIGTERM and poll
 * /proc/comm until gone.
 *
 * <p><strong>LinuxKit limitation (Docker Desktop on macOS/Windows):</strong>
 *
 * <p>Running stress-ng with timeout=0 does not exit on SIGTERM under LinuxKit because LinuxKit
 * intercepts signals before the process handler runs. This is a kernel limitation, not a code bug.
 * Tests relying on SIGTERM reset should be annotated with DisabledOnNonLinuxHost.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see CpuCommandBuilder
 */
public final class StressNgCommandBuilder implements CpuCommandBuilder {

  /** Shared singleton - all methods are pure functions with no state. */
  public static final StressNgCommandBuilder INSTANCE = new StressNgCommandBuilder();

  /** Creates a new {@code StressNgCommandBuilder} instance. */
  public StressNgCommandBuilder() {
    // Stateless - safe to instantiate or use as singleton.
  }

  // ==================== Process Lifecycle ====================

  @Override
  public String buildFindLowestPidByCommCommand(final String exactCommName) {
    Objects.requireNonNull(exactCommName, "exactCommName must not be null");

    // Iterate /proc/[0-9]*/comm numerically sorted (lowest PID first via shell glob order).
    // Outputs the first match - that is the parent when multiple workers are running.
    return String.format(
        "for f in /proc/[0-9]*/comm; do "
            + "[ \"$(cat \"$f\" 2>/dev/null)\" = \"%s\" ] "
            + "&& echo \"${f%%/comm}\" | grep -o '[0-9]*$' && break; "
            + "done",
        exactCommName);
  }

  @Override
  public String buildIsRunningByCommExactCommand(final String exactCommName) {
    Objects.requireNonNull(exactCommName, "exactCommName must not be null");

    // grep -rl scans all /proc/*/comm files for an exact name match; exits 0 on first hit.
    return String.format(
        "grep -rl '^%s$' /proc/[0-9]*/comm 2>/dev/null | grep -q .",
        exactCommName);
  }

  @Override
  public String buildIsRunningByCommPrefixCommand(final String commPrefix) {
    Objects.requireNonNull(commPrefix, "commPrefix must not be null");

    // Prefix match covers "stress-ng", "stress-ng-cpu", "stress-ng-cache", etc.
    return String.format(
        "grep -rl '^%s' /proc/[0-9]*/comm 2>/dev/null | grep -q .",
        commPrefix);
  }

  @Override
  public String buildKillAllByCommSigKillCommand(final String exactCommName) {
    Objects.requireNonNull(exactCommName, "exactCommName must not be null");

    // Iterate every /proc/comm entry - kill -9 all exact matches.
    // Idempotent: always exits 0 (|| true).
    return String.format(
        "for f in /proc/[0-9]*/comm; do "
            + "if [ \"$(cat \"$f\" 2>/dev/null)\" = \"%s\" ]; then "
            + "  p=\"${f%%/comm}\"; p=\"${p##*/}\"; "
            + "  kill -9 \"$p\" 2>/dev/null; "
            + "fi; "
            + "done; true",
        exactCommName);
  }

  @Override
  public String buildKillAllByCommPrefixSigKillCommand(final String commPrefix) {
    Objects.requireNonNull(commPrefix, "commPrefix must not be null");

    // Prefix match via grep: kills parent + all worker variants
    // (stress-ng-cpu, stress-ng-cache, stress-ng-cacheline, etc.).
    // grep -rl finds matching /proc/<pid>/comm files, then extract PID and SIGKILL.
    return String.format(
        "for f in $(grep -rl '^%s' /proc/[0-9]*/comm 2>/dev/null); do "
            + "p=\"${f%%/comm}\"; p=\"${p##*/}\"; "
            + "kill -9 \"$p\" 2>/dev/null; "
            + "done; true",
        commPrefix);
  }

  @Override
  public String buildKillParentByCommSigTermCommand(final String exactCommName) {
    Objects.requireNonNull(exactCommName, "exactCommName must not be null");

    // Find lowest matching PID (parent), send SIGTERM, break immediately.
    // Worker children are killed by the parent's shutdown handler - never SIGKILL workers.
    return String.format(
        "for f in /proc/[0-9]*/comm; do "
            + "if [ \"$(cat \"$f\" 2>/dev/null)\" = \"%s\" ]; then "
            + "  p=\"${f%%/comm}\"; p=\"${p##*/}\"; "
            + "  kill -15 \"$p\" 2>/dev/null; "
            + "  break; "
            + "fi; "
            + "done; true",
        exactCommName);
  }

  // ==================== stress-ng Stressor Commands ====================

  @Override
  public String buildStressCpuCommand(final int workers) {
    validateWorkers(workers);
    return String.format("stress-ng --cpu %d --timeout 0 >/dev/null 2>&1 &", workers);
  }

  @Override
  public String buildStressCpuWithTimeoutCommand(final int workers, final long seconds) {
    validateWorkers(workers);
    validateSeconds(seconds);
    return String.format("stress-ng --cpu %d --timeout %ds >/dev/null 2>&1 &", workers, seconds);
  }

  @Override
  public String buildStressCacheCommand(final int workers) {
    validateWorkers(workers);
    return String.format("stress-ng --cache %d --timeout 0 >/dev/null 2>&1 &", workers);
  }

  @Override
  public String buildStressCacheWithTimeoutCommand(final int workers, final long seconds) {
    validateWorkers(workers);
    validateSeconds(seconds);
    return String.format("stress-ng --cache %d --timeout %ds >/dev/null 2>&1 &", workers, seconds);
  }

  @Override
  public String buildStressCacheLineCommand(final int workers) {
    validateWorkers(workers);
    return String.format("stress-ng --cacheline %d --timeout 0 >/dev/null 2>&1 &", workers);
  }

  @Override
  public String buildStressContextSwitchCommand(final int workers) {
    validateWorkers(workers);
    return String.format("stress-ng --context %d --timeout 0 >/dev/null 2>&1 &", workers);
  }

  @Override
  public String buildStressThreadSwitchCommand(final int workers) {
    validateWorkers(workers);
    return String.format("stress-ng --switch %d --timeout 0 >/dev/null 2>&1 &", workers);
  }

  @Override
  public String buildStressBranchPredictorCommand(final int workers) {
    validateWorkers(workers);
    return String.format("stress-ng --branch %d --timeout 0 >/dev/null 2>&1 &", workers);
  }

  @Override
  public String buildStressTimerInterruptsCommand(final int workers) {
    validateWorkers(workers);
    return String.format("stress-ng --hrtimers %d --timeout 0 >/dev/null 2>&1 &", workers);
  }

  @Override
  public String buildStressMatrixCommand(final int workers) {
    validateWorkers(workers);
    return String.format("stress-ng --matrix %d --timeout 0 >/dev/null 2>&1 &", workers);
  }

  @Override
  public String buildStressMatrixWithTimeoutCommand(final int workers, final long seconds) {
    validateWorkers(workers);
    validateSeconds(seconds);
    return String.format(
        "stress-ng --matrix %d --timeout %ds >/dev/null 2>&1 &", workers, seconds);
  }

  // ==================== cpulimit Commands ====================

  @Override
  public String buildThrottleCommand(final int pid, final int percentage) {
    validatePid(pid);
    validatePercentage(percentage);
    return String.format("cpulimit -l %d -p %d >/dev/null 2>&1 &", percentage, pid);
  }

  @Override
  public String buildThrottleWithDurationCommand(
      final int pid, final int percentage, final long seconds) {
    validatePid(pid);
    validatePercentage(percentage);
    validateSeconds(seconds);
    // Background subshell: start cpulimit, capture its PID, sleep, then kill it.
    // Container-internal timer - no Java thread or scheduler involved.
    return String.format(
        "(cpulimit -l %d -p %d >/dev/null 2>&1 & CPID=$!; sleep %d; kill $CPID 2>/dev/null) &",
        percentage, pid, seconds);
  }

  // ==================== taskset Commands ====================

  @Override
  public String buildPinToMaskCommand(final int pid, final long affinityMask) {
    validatePid(pid);
    if (affinityMask <= 0) {
      throw new ChaosConfigurationException(
          "affinityMask must be > 0, got: 0x" + Long.toHexString(affinityMask));
    }
    // taskset -p <hex-mask> <pid> - applies to an already-running process.
    return String.format("taskset -p 0x%x %d", affinityMask, pid);
  }

  @Override
  public String buildGetAffinityMaskCommand(final int pid) {
    validatePid(pid);
    // Output: "pid N's current affinity mask: <hexmask>"
    return String.format("taskset -p %d", pid);
  }

  // ==================== System Info Commands ====================

  @Override
  public String buildGetCoreCountCommand() {
    return "nproc";
  }

  @Override
  public String buildGetCoreCountFallbackCommand() {
    // Count "processor" lines - covers x86, ARM, RISC-V, MIPS, etc.
    return "grep -c '^processor' /proc/cpuinfo";
  }

  @Override
  public String buildReadCpuStatCommand() {
    return "cat /proc/stat";
  }

  // ==================== renice Commands ====================

  @Override
  public String buildSetNiceValueCommand(final int pid, final int niceValue) {
    validatePid(pid);
    // No range validation here - let renice fail with a real error if the caller passes
    // a value that requires capabilities the container does not have.
    return String.format("renice %d -p %d", niceValue, pid);
  }

  @Override
  public String buildGetNiceValueCommand(final int pid) {
    validatePid(pid);
    // /proc/<pid>/stat field 19 (1-indexed) is the nice value.
    // awk is guaranteed present on all Linux container base images.
    return String.format("awk '{print $19}' /proc/%d/stat", pid);
  }

  // ==================== Private Validators ====================

  private static void validatePid(final int pid) {
    if (pid < 1) {
      throw new IllegalArgumentException("pid must be >= 1, got: " + pid);
    }
  }

  private static void validateWorkers(final int workers) {
    if (workers < 1) {
      throw new ChaosConfigurationException("workers must be >= 1, got: " + workers);
    }
  }

  private static void validatePercentage(final int percentage) {
    if (percentage < 1 || percentage > 100) {
      throw new ChaosConfigurationException(
          String.format("percentage must be in [1, 100], got: %d", percentage));
    }
  }

  private static void validateSeconds(final long seconds) {
    if (seconds < 1) {
      throw new IllegalArgumentException("seconds must be >= 1, got: " + seconds);
    }
  }
}
