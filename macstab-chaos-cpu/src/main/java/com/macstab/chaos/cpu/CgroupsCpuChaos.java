/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.CpuChaos;
import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.Shell;
import com.macstab.chaos.cpu.command.StressNgCommandBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * CPU chaos using inside-container userspace tools ({@code stress-ng}, {@code cpulimit}, {@code
 * taskset}, {@code renice}).
 *
 * <p>All operations work in any unprivileged Linux container without additional capabilities,
 * cgroup write access, or privileged mode. Command construction is fully delegated to {@link
 * StressNgCommandBuilder}. Observability queries are delegated to {@link CpuObservability}.
 *
 * <h2>Init-Aware PID Resolution</h2>
 *
 * <p>Operations that target the main application process (throttle, affinity pinning, priority
 * degradation) must know its PID. In containers without an init system, PID 1 <em>is</em> the
 * application. But when {@code --init} is used (recommended for zombie reaping), PID 1 is the
 * init system (tini, dumb-init, etc.) and the application runs as a child.
 *
 * <p>This class transparently handles both cases via {@code resolveMainPid}:
 *
 * <pre>
 * WITHOUT --init:              WITH --init:
 * PID 1: redis-server          PID 1: tini
 *                               PID 7: redis-server
 *
 * resolveMainPid = 1           resolveMainPid = 7
 * </pre>
 *
 * <p>Detection reads {@code /proc/1/comm} to identify known init systems, then finds the first
 * child via {@code /proc/1/task/1/children}. The result is cached on the container Java object
 * via label {@code macstab.chaos.pid.main} -- zero overhead after first resolution.
 *
 * <p><strong>User override:</strong> Set the label before the first chaos operation to force
 * a specific PID:
 *
 * <pre>{@code
 * container.withLabel("macstab.chaos.pid.main", "42");
 * }</pre>
 *
 * <h2>Zombie-Aware Process Detection</h2>
 *
 * <p>Process detection via /proc/[0-9]&#42;/comm skips zombie processes (state Z in
 * /proc/[pid]/stat field 3). SIGKILL'd processes become zombies until reaped by their
 * parent. Without filtering, isStressed() would return true for dead processes whose
 * /proc entry is still readable. Using --init (tini) ensures zombies are reaped within
 * milliseconds. The detection filter is defense-in-depth.
 *
 * <p>Registered as the {@code CpuChaos} SPI provider via {@code
 * META-INF/services/com.macstab.chaos.core.api.CpuChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CgroupsCpuChaos implements CpuChaos {

  /** Delay after starting stress-ng before querying its PID via /proc/comm. */
  private static final long STRESS_NG_STARTUP_MS = 50;

  /** Max time to wait for a backgrounded process to appear in /proc. */
  private static final long PROCESS_STARTUP_TIMEOUT_MS = 2_000;

  /** Poll interval for process startup detection. */
  private static final long STARTUP_POLL_INTERVAL_MS = 10;

  /** Timeout for stress-ng process to disappear after SIGKILL + tini reap. */
  private static final long STRESS_NG_SHUTDOWN_TIMEOUT_MS = 1_000;

  /** Timeout for cpulimit process to disappear after SIGKILL + tini reap. */
  private static final long CPULIMIT_SHUTDOWN_TIMEOUT_MS = 1_000;

  /** Poll interval for waitUntilGone loop. */
  private static final long WAIT_POLL_INTERVAL_MS = 50;

  /** Label key for caching the resolved main application PID. */
  private static final String LABEL_MAIN_PID = "macstab.chaos.pid.main";

  /** Known init process names that are NOT the main application. */
  private static final java.util.Set<String> KNOWN_INIT_NAMES =
      java.util.Set.of("tini", "dumb-init", "s6-svscan", "s6-supervise");

  private final StressNgCommandBuilder cmd = StressNgCommandBuilder.INSTANCE;
  private final CpuObservability observe = new CpuObservability(cmd);

  // ==================== Throttling ====================

  @Override
  public void throttle(final GenericContainer<?> container, final int percentage) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);
    installTools(container);
    killCpuLimit(container);
    // percentage range validated by builder (throws ChaosConfigurationException)
    exec(container, cmd.buildThrottleCommand(resolveMainPid(container), percentage), "throttle CPU");
    waitUntilStarted(container, "cpulimit");
    log.info("Throttled CPU to {}% (PID 1)", percentage);
  }

  @Override
  public void throttle(
      final GenericContainer<?> container, final int percentage, final Duration duration) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.toSeconds() <= 0) {
      throw new ChaosConfigurationException("duration must be > 0, got: " + duration);
    }
    validateContainerRunning(container);
    installTools(container);
    // percentage range validated by builder (throws ChaosConfigurationException)
    exec(
        container,
        cmd.buildThrottleWithDurationCommand(1, percentage, duration.toSeconds()),
        "throttle CPU with duration");
    waitUntilStarted(container, "cpulimit");
    log.info("Throttled CPU to {}% for {}s (auto-reset)", percentage, duration.toSeconds());
  }

  // ==================== Stress Injection ====================

  @Override
  public void stress(final GenericContainer<?> container, final int workers) {
    runStressor(container, workers, () -> cmd.buildStressCpuCommand(workers), "CPU");
  }

  @Override
  public void stress(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    runStressorWithTimeout(
        container,
        workers,
        duration,
        () -> cmd.buildStressCpuWithTimeoutCommand(workers, duration.toSeconds()),
        "CPU");
  }

  @Override
  public void stressWithThrottle(
      final GenericContainer<?> container, final int workers, final int percentage) {
    Objects.requireNonNull(container, "container must not be null");
    // Build commands eagerly — builder validates workers/percentage, throws
    // ChaosConfigurationException
    final String startStressCmd = cmd.buildStressCpuCommand(workers);
    final String startThrottleTemplate =
        cmd.buildThrottleCommand(resolveMainPid(container), percentage); // validates percentage; pid replaced later
    validateContainerRunning(container);
    installTools(container);
    killStressNg(container);
    killCpuLimit(container);

    try {
      execShell(container, startStressCmd);
      Thread.sleep(STRESS_NG_STARTUP_MS);

      final var pidResult =
          container.execInContainer(
              Shell.SH, Shell.FLAG_C, cmd.buildFindLowestPidByCommCommand("stress-ng"));
      if (pidResult.getExitCode() != 0 || pidResult.getStdout().isBlank()) {
        throw new ChaosOperationFailedException("stress-ng did not start");
      }
      final int stressPid = Integer.parseInt(pidResult.getStdout().trim());
      execShell(container, cmd.buildThrottleCommand(stressPid, percentage));
      log.info("Started stress+throttle: {} workers at {}%", workers, percentage);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start stress with throttle", e);
    }
  }

  @Override
  public void stressCache(final GenericContainer<?> container, final int workers) {
    runStressor(container, workers, () -> cmd.buildStressCacheCommand(workers), "cache");
  }

  @Override
  public void stressCache(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    runStressorWithTimeout(
        container,
        workers,
        duration,
        () -> cmd.buildStressCacheWithTimeoutCommand(workers, duration.toSeconds()),
        "cache");
  }

  @Override
  public void stressCacheLine(final GenericContainer<?> container, final int workers) {
    runStressor(container, workers, () -> cmd.buildStressCacheLineCommand(workers), "cache-line");
  }

  @Override
  public void stressContextSwitch(final GenericContainer<?> container, final int workers) {
    runStressor(
        container, workers, () -> cmd.buildStressContextSwitchCommand(workers), "context-switch");
  }

  @Override
  public void stressThreadSwitch(final GenericContainer<?> container, final int workers) {
    runStressor(
        container, workers, () -> cmd.buildStressThreadSwitchCommand(workers), "thread-switch");
  }

  @Override
  public void stressBranchPredictor(final GenericContainer<?> container, final int workers) {
    runStressor(
        container,
        workers,
        () -> cmd.buildStressBranchPredictorCommand(workers),
        "branch-predictor");
  }

  @Override
  public void stressTimerInterrupts(final GenericContainer<?> container, final int workers) {
    runStressor(
        container,
        workers,
        () -> cmd.buildStressTimerInterruptsCommand(workers),
        "timer-interrupt");
  }

  @Override
  public void stressMatrix(final GenericContainer<?> container, final int workers) {
    runStressor(container, workers, () -> cmd.buildStressMatrixCommand(workers), "matrix");
  }

  @Override
  public void stressMatrix(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    runStressorWithTimeout(
        container,
        workers,
        duration,
        () -> cmd.buildStressMatrixWithTimeoutCommand(workers, duration.toSeconds()),
        "matrix");
  }

  // ==================== CPU Affinity ====================

  @Override
  public void pinToCoreMask(final GenericContainer<?> container, final long affinityMask) {
    Objects.requireNonNull(container, "container must not be null");
    if (affinityMask <= 0) {
      throw new ChaosConfigurationException(
          "affinityMask must be > 0, got: 0x" + Long.toHexString(affinityMask));
    }
    validateContainerRunning(container);
    exec(
        container,
        cmd.buildPinToMaskCommand(resolveMainPid(container), affinityMask),
        "pin CPU affinity to mask 0x" + Long.toHexString(affinityMask));
    log.info("Pinned PID 1 to CPU mask 0x{}", Long.toHexString(affinityMask));
  }

  // ==================== Process Priority ====================

  @Override
  public void degradePriority(final GenericContainer<?> container, final int niceValue) {
    Objects.requireNonNull(container, "container must not be null");
    if (niceValue < 0 || niceValue > 19) {
      throw new ChaosConfigurationException(
          "niceValue must be in [0, 19] for unprivileged containers, got: " + niceValue);
    }
    validateContainerRunning(container);
    exec(container, cmd.buildSetNiceValueCommand(resolveMainPid(container), niceValue), "degrade CPU priority");
    log.info("Degraded PID 1 priority to nice={}", niceValue);
  }

  @Override
  public void resetPriority(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!container.isRunning()) {
      return;
    }
    trySilent(container, cmd.buildSetNiceValueCommand(resolveMainPid(container), 0));
    log.info("Reset PID 1 priority to nice=0");
  }

  // ==================== Observability (delegated) ====================

  @Override
  public int getCurrentUsage(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);
    try {
      return observe.getCurrentUsage(container);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to read CPU usage", e);
    }
  }

  @Override
  public int getAvailableCores(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);
    try {
      return observe.getAvailableCores(container);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to get available cores", e);
    }
  }

  @Override
  public boolean isThrottled(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.isRunning() && observe.isThrottled(container);
  }

  @Override
  public boolean isStressed(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.isRunning() && observe.isStressed(container);
  }

  @Override
  public boolean isAffinityPinned(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.isRunning() && observe.isAffinityPinned(container, resolveMainPid(container));
  }

  @Override
  public long getPinnedCoreMask(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);
    return observe.readAffinityMask(container, resolveMainPid(container));
  }

  @Override
  public int getNiceValue(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);
    try {
      return observe.getNiceValue(container, resolveMainPid(container));
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to read nice value", e);
    }
  }

  // ==================== Lifecycle ====================

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);
    PackageInstaller.ensureInstalled(
        container,
        Tool.STRESS_NG,
        Tool.CPULIMIT,
        Tool.TASKSET,
        Tool.RENICE,
        Tool.NPROC,
        Tool.PROCPS);
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!container.isRunning()) {
      return;
    }

    killCpuLimit(container);
    killStressNg(container);
    waitUntilGone(container, "stress-ng", STRESS_NG_SHUTDOWN_TIMEOUT_MS);
    waitUntilGone(container, "cpulimit", CPULIMIT_SHUTDOWN_TIMEOUT_MS);

    final int cores = observe.getAvailableCoresSilent(container);
    trySilent(container, cmd.buildPinToMaskCommand(resolveMainPid(container), CpuObservability.computeFullMask(cores)));
    trySilent(container, cmd.buildSetNiceValueCommand(resolveMainPid(container), 0));

    log.info("Reset CPU chaos");
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  // ==================== Private: Stressor Templates ====================

  /**
   * Template for indefinite stressor methods. Validates first, then builds command, installs, kills
   * previous, executes, logs. Command is deferred via {@link Supplier} to ensure validation
   * exceptions ({@link ChaosConfigurationException}) are thrown before builder-level {@link
   * IllegalArgumentException}.
   */
  /**
   * Template for indefinite stressor methods. The {@code commandSupplier} is invoked first so that
   * builder validation (workers range → {@link ChaosConfigurationException}) fires before any
   * container side effects.
   */
  private void runStressor(
      final GenericContainer<?> container,
      final int workers,
      final Supplier<String> commandSupplier,
      final String label) {
    Objects.requireNonNull(container, "container must not be null");
    // Invoke supplier first: builder validates workers, throws ChaosConfigurationException if
    // invalid
    final String command = commandSupplier.get();
    validateContainerRunning(container);
    installTools(container);
    killStressNg(container);
    exec(container, command, label + " stress");
    log.info("Started {} stress: {} workers", label, workers);
  }

  /**
   * Template for time-bounded stressor methods. Command is built (and validated) before any
   * container side effects.
   */
  private void runStressorWithTimeout(
      final GenericContainer<?> container,
      final int workers,
      final Duration duration,
      final Supplier<String> commandSupplier,
      final String label) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
    // Invoke supplier first: builder validates workers, throws ChaosConfigurationException if
    // invalid
    final String command = commandSupplier.get();
    validateContainerRunning(container);
    installTools(container);
    killStressNg(container);
    exec(container, command, label + " stress with timeout");
    log.info("Started {} stress: {} workers for {}s", label, workers, duration.toSeconds());
  }

  // ==================== Private: Execution ====================

  /** Executes a shell command; throws ChaosOperationFailedException on non-zero exit. */
  private void exec(final GenericContainer<?> container, final String command, final String label) {
    try {
      execShell(container, command);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to " + label, e);
    }
  }

  private void execShell(final GenericContainer<?> container, final String command)
      throws Exception {
    final var result = container.execInContainer(Shell.SH, Shell.FLAG_C, command);
    if (result.getExitCode() != 0) {
      throw new ChaosOperationFailedException("Shell command failed: " + result.getStderr());
    }
  }

  private void trySilent(final GenericContainer<?> container, final String command) {
    try {
      final var result = container.execInContainer(Shell.SH, Shell.FLAG_C, command);
      log.debug("trySilent exit={}", result.getExitCode());
    } catch (final Exception e) {
      log.debug("trySilent ignored: {}", e.getMessage());
    }
  }

  // ==================== Private: Process Lifecycle ====================

  private void killCpuLimit(final GenericContainer<?> container) {
    trySilent(container, cmd.buildKillAllByCommSigKillCommand("cpulimit"));
  }

  private void killStressNg(final GenericContainer<?> container) {
    // SIGKILL the parent first — parent dies, workers become orphans.
    // Then SIGKILL all remaining workers (which now can't be respawned).
    // SIGTERM is unreliable: Docker Desktop's LinuxKit kernel may not deliver it,
    // and PID 1 in containers ignores SIGTERM unless it registers a handler.
    trySilent(container, cmd.buildKillAllByCommPrefixSigKillCommand("stress-ng"));
  }

  private void waitUntilGone(
      final GenericContainer<?> container, final String name, final long timeoutMs) {
    // Fast grep-based check — with tini as init, processes are fully reaped (no zombies).
    // The zombie-aware filter in buildIsRunningByCommPrefixCommand is for user-facing
    // observability (isStressed/isThrottled), not for internal cleanup polling.
    final String fastCheck = String.format(
        "grep -rl '^%s' /proc/[0-9]*/comm 2>/dev/null | grep -q .", name);
    final long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        final var result = container.execInContainer(Shell.SH, Shell.FLAG_C, fastCheck);
        if (result.getExitCode() != 0) {
          return;
        }
        Thread.sleep(WAIT_POLL_INTERVAL_MS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (final Exception e) {
        return;
      }
    }
    log.warn("waitUntilGone: {} did not exit within {}ms", name, timeoutMs);
  }

  // ==================== Private: PID Resolution ====================

  /**
   * Resolves the main application PID inside the container.
   *
   * <p>Many containers use an init system ({@code --init} flag / tini / dumb-init) as PID 1
   * to properly reap zombie processes and forward signals. In these containers, the main
   * application is <em>not</em> PID 1 — it's a child of the init process.
   *
   * <p>This method transparently detects both configurations:
   *
   * <pre>{@code
   * Container without --init:     Container with --init:
   * PID 1: redis-server            PID 1: tini
   * → resolveMainPid = 1            PID 7: redis-server
   *                                → resolveMainPid = 7
   * }</pre>
   *
   * <p><strong>Detection algorithm:</strong>
   * <ol>
   *   <li>Check label {@code macstab.chaos.pid.main} — if set (cached or user-override), return it
   *   <li>Read {@code /proc/1/comm} — compare against known init names (tini, dumb-init, s6)
   *   <li>If init detected: read {@code /proc/1/task/1/children} → first child PID = main app
   *   <li>If no init: PID 1 = main application
   * </ol>
   *
   * <p><strong>Result caching:</strong> Stored via {@link GenericContainer#withLabel} on the
   * container's Java object — pure in-JVM, zero Docker API overhead. Subsequent calls
   * return the cached PID from a HashMap lookup.
   *
   * <p><strong>User override:</strong> Set the label before the first chaos operation:
   * <pre>{@code
   * container.withLabel("macstab.chaos.pid.main", "42");
   * }</pre>
   *
   * @param container target container (must be running)
   * @return main application PID (never 0, defaults to 1 on detection failure)
   */
  private int resolveMainPid(final GenericContainer<?> container) {
    // Check label cache (or user override)
    final String cached = container.getLabels().get(LABEL_MAIN_PID);
    if (cached != null) {
      return Integer.parseInt(cached);
    }

    int pid = 1; // default
    try {
      final var commResult = container.execInContainer(Shell.SH, Shell.FLAG_C, "cat /proc/1/comm");
      if (commResult.getExitCode() == 0) {
        final String comm = commResult.getStdout().trim();
        if (KNOWN_INIT_NAMES.contains(comm)) {
          // PID 1 is an init system — find the main application (first child)
          final var childResult = container.execInContainer(
              Shell.SH, Shell.FLAG_C, "cat /proc/1/task/1/children");
          if (childResult.getExitCode() == 0 && !childResult.getStdout().isBlank()) {
            final String firstChild = childResult.getStdout().trim().split("\\s+")[0];
            pid = Integer.parseInt(firstChild);
            log.info("Init detected ({}), main application PID: {}", comm, pid);
          }
        }
      }
    } catch (final Exception e) {
      log.debug("PID resolution failed, defaulting to PID 1: {}", e.getMessage());
    }

    container.withLabel(LABEL_MAIN_PID, String.valueOf(pid));
    return pid;
  }

  // ==================== Private: Startup Detection ====================

  /**
   * Polls until a backgrounded process appears in /proc/comm.
   *
   * <p>Background processes ({@code cmd &}) return exit 0 immediately from the shell.
   * The actual process may not yet be visible in {@code /proc} when the exec returns.
   * This method polls until the process appears or the timeout expires.
   *
   * @param container target container
   * @param commName  exact comm name to wait for (e.g. "cpulimit", "stress-ng")
   */
  private void waitUntilStarted(final GenericContainer<?> container, final String commName) {
    final String check = cmd.buildIsRunningByCommExactCommand(commName);
    final long deadline = System.currentTimeMillis() + PROCESS_STARTUP_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      try {
        final var result = container.execInContainer(Shell.SH, Shell.FLAG_C, check);
        if (result.getExitCode() == 0) {
          return;
        }
        Thread.sleep(STARTUP_POLL_INTERVAL_MS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (final Exception e) {
        return;
      }
    }
    log.warn("waitUntilStarted: {} did not appear within {}ms", commName, PROCESS_STARTUP_TIMEOUT_MS);
  }

  // ==================== Private: Validation ====================

  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }
  }
}
