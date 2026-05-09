/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
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
 * application. But when {@code --init} is used (recommended for zombie reaping), PID 1 is the init
 * system (tini, dumb-init, etc.) and the application runs as a child.
 *
 * <p>This class transparently handles both cases via {@link ContainerPidResolver}:
 *
 * <pre>
 * WITHOUT --init:              WITH --init:
 * PID 1: redis-server          PID 1: tini
 *                               PID 7: redis-server
 *
 * resolved PID = 1             resolved PID = 7
 * </pre>
 *
 * <p>Detection reads {@code /proc/1/comm} to identify known init systems, then finds the first
 * child via {@code /proc/1/task/1/children}. The result is cached on the container Java object via
 * label {@code macstab.chaos.pid.main} -- zero overhead after first resolution.
 *
 * <p><strong>User override:</strong> Set the label before the first chaos operation to force a
 * specific PID:
 *
 * <pre>{@code
 * container.withLabel("macstab.chaos.pid.main", "42");
 * }</pre>
 *
 * <h2>Zombie-Aware Process Detection</h2>
 *
 * <p>Process detection via /proc/[0-9]&#42;/comm skips zombie processes (state Z in
 * /proc/[pid]/stat field 3). SIGKILL'd processes become zombies until reaped by their parent.
 * Without filtering, isStressed() would return true for dead processes whose /proc entry is still
 * readable. Using --init (tini) ensures zombies are reaped within milliseconds. The detection
 * filter is defense-in-depth.
 *
 * <p>Registered as the {@code CpuChaos} SPI provider via {@code
 * META-INF/services/com.macstab.chaos.core.api.CpuChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CgroupsCpuChaos implements CpuChaos {

  /**
   * Delay after starting stress-ng before querying its PID for stressWithThrottle.
   *
   * <p>50ms: stress-ng forks worker processes immediately; the parent PID is visible in /proc
   * within 1-2ms on modern kernels. 50ms provides comfortable headroom across DinD, Docker Desktop,
   * and native Linux environments.
   */
  private static final long STRESS_NG_STARTUP_MS = 50;

  /**
   * Maximum time to wait for stress-ng to disappear after SIGKILL.
   *
   * <p>With tini/docker-init as PID 1, orphaned processes are reaped within milliseconds. 1000ms is
   * a generous upper bound for slow DinD environments.
   */
  private static final long STRESS_NG_SHUTDOWN_TIMEOUT_MS = 1_000;

  /**
   * Maximum time to wait for cpulimit to disappear after SIGKILL.
   *
   * <p>Same rationale as {@link #STRESS_NG_SHUTDOWN_TIMEOUT_MS}.
   */
  private static final long CPULIMIT_SHUTDOWN_TIMEOUT_MS = 1_000;

  /**
   * Maximum time to wait for a backgrounded process to appear in /proc after exec.
   *
   * <p>Background processes ({@code cmd &}) may not be visible in /proc when the shell exec
   * returns. 2000ms covers the slowest DinD environments observed.
   */
  private static final long PROCESS_STARTUP_TIMEOUT_MS = 2_000;

  private final StressNgCommandBuilder cmd;
  private final CpuObservability observe;

  /** Production constructor — uses shared singleton command builder. */
  public CgroupsCpuChaos() {
    this.cmd = StressNgCommandBuilder.INSTANCE;
    this.observe = new CpuObservability(cmd);
  }

  /**
   * Package-private testability constructor — accepts collaborators for unit testing.
   *
   * @param cmd command builder (mock or real)
   * @param observe observability delegate (mock or real)
   */
  CgroupsCpuChaos(final StressNgCommandBuilder cmd, final CpuObservability observe) {
    this.cmd = Objects.requireNonNull(cmd, "cmd must not be null");
    this.observe = Objects.requireNonNull(observe, "observe must not be null");
  }

  // ==================== Throttling ====================

  @Override
  public void throttle(final GenericContainer<?> container, final int percentage) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);
    installTools(container);
    killCpuLimit(container);
    // percentage range validated by builder (throws ChaosConfigurationException)
    exec(
        container,
        cmd.buildThrottleCommand(ContainerPidResolver.resolve(container), percentage),
        "throttle CPU");
    ProcessPoller.waitUntilStarted(container, "cpulimit", PROCESS_STARTUP_TIMEOUT_MS);
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
        cmd.buildThrottleWithDurationCommand(
            ContainerPidResolver.resolve(container), percentage, duration.toSeconds()),
        "throttle CPU with duration");
    ProcessPoller.waitUntilStarted(container, "cpulimit", PROCESS_STARTUP_TIMEOUT_MS);
    log.info("Throttled CPU to {}% for {}s (auto-reset)", percentage, duration.toSeconds());
  }

  // ==================== Stress Injection ====================

  @Override
  public void stress(final GenericContainer<?> container, final int workers) {
    executeStressor(
        container, workers, () -> cmd.buildStressCpuCommand(workers), "CPU", Optional.empty());
  }

  @Override
  public void stress(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    executeStressor(
        container,
        workers,
        () -> cmd.buildStressCpuWithTimeoutCommand(workers, duration.toSeconds()),
        "CPU",
        Optional.of(duration));
  }

  @Override
  public void stressWithThrottle(
      final GenericContainer<?> container, final int workers, final int percentage) {
    Objects.requireNonNull(container, "container must not be null");
    // Build commands eagerly — builder validates workers/percentage, throws
    // ChaosConfigurationException
    final String startStressCmd = cmd.buildStressCpuCommand(workers);
    final String startThrottleTemplate =
        cmd.buildThrottleCommand(
            ContainerPidResolver.resolve(container),
            percentage); // validates percentage; pid replaced later
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
    executeStressor(
        container, workers, () -> cmd.buildStressCacheCommand(workers), "cache", Optional.empty());
  }

  @Override
  public void stressCache(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    executeStressor(
        container,
        workers,
        () -> cmd.buildStressCacheWithTimeoutCommand(workers, duration.toSeconds()),
        "cache",
        Optional.of(duration));
  }

  @Override
  public void stressCacheLine(final GenericContainer<?> container, final int workers) {
    executeStressor(
        container,
        workers,
        () -> cmd.buildStressCacheLineCommand(workers),
        "cache-line",
        Optional.empty());
  }

  @Override
  public void stressContextSwitch(final GenericContainer<?> container, final int workers) {
    executeStressor(
        container,
        workers,
        () -> cmd.buildStressContextSwitchCommand(workers),
        "context-switch",
        Optional.empty());
  }

  @Override
  public void stressThreadSwitch(final GenericContainer<?> container, final int workers) {
    executeStressor(
        container,
        workers,
        () -> cmd.buildStressThreadSwitchCommand(workers),
        "thread-switch",
        Optional.empty());
  }

  @Override
  public void stressBranchPredictor(final GenericContainer<?> container, final int workers) {
    executeStressor(
        container,
        workers,
        () -> cmd.buildStressBranchPredictorCommand(workers),
        "branch-predictor",
        Optional.empty());
  }

  @Override
  public void stressTimerInterrupts(final GenericContainer<?> container, final int workers) {
    executeStressor(
        container,
        workers,
        () -> cmd.buildStressTimerInterruptsCommand(workers),
        "timer-interrupt",
        Optional.empty());
  }

  @Override
  public void stressMatrix(final GenericContainer<?> container, final int workers) {
    executeStressor(
        container,
        workers,
        () -> cmd.buildStressMatrixCommand(workers),
        "matrix",
        Optional.empty());
  }

  @Override
  public void stressMatrix(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    executeStressor(
        container,
        workers,
        () -> cmd.buildStressMatrixWithTimeoutCommand(workers, duration.toSeconds()),
        "matrix",
        Optional.of(duration));
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
        cmd.buildPinToMaskCommand(ContainerPidResolver.resolve(container), affinityMask),
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
    exec(
        container,
        cmd.buildSetNiceValueCommand(ContainerPidResolver.resolve(container), niceValue),
        "degrade CPU priority");
    log.info("Degraded PID 1 priority to nice={}", niceValue);
  }

  @Override
  public void resetPriority(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!container.isRunning()) {
      return;
    }
    trySilent(container, cmd.buildSetNiceValueCommand(ContainerPidResolver.resolve(container), 0));
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
    return container.isRunning()
        && observe.isAffinityPinned(container, ContainerPidResolver.resolve(container));
  }

  @Override
  public long getPinnedCoreMask(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);
    return observe.readAffinityMask(container, ContainerPidResolver.resolve(container));
  }

  @Override
  public int getNiceValue(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);
    try {
      return observe.getNiceValue(container, ContainerPidResolver.resolve(container));
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
    ProcessPoller.waitUntilGone(container, "stress-ng", STRESS_NG_SHUTDOWN_TIMEOUT_MS);
    ProcessPoller.waitUntilGone(container, "cpulimit", CPULIMIT_SHUTDOWN_TIMEOUT_MS);

    final int cores = observe.getAvailableCoresSilent(container);
    trySilent(
        container,
        cmd.buildPinToMaskCommand(
            ContainerPidResolver.resolve(container), CpuObservability.computeFullMask(cores)));
    trySilent(container, cmd.buildSetNiceValueCommand(ContainerPidResolver.resolve(container), 0));

    log.info("Reset CPU chaos");
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  // ==================== Private: Stressor Template ====================

  /**
   * Unified template for all stressor executions — indefinite and time-bounded.
   *
   * <p>The {@code commandSupplier} is invoked <em>before</em> any container side-effects so that
   * builder-level validation ({@link ChaosConfigurationException} for invalid workers or duration)
   * fires immediately without touching the container.
   *
   * <p>The optional {@code duration} is used only for log output. The command string returned by
   * {@code commandSupplier} already encodes the timeout via {@code --timeout Xs}.
   *
   * @param container target container (must be running)
   * @param workers number of stressor workers (for log output)
   * @param commandSupplier builds the shell command; validated eagerly before container ops
   * @param label human-readable stressor name (e.g. {@code "CPU"}, {@code "cache"})
   * @param duration present for time-bounded runs; empty for indefinite runs
   */
  private void executeStressor(
      final GenericContainer<?> container,
      final int workers,
      final Supplier<String> commandSupplier,
      final String label,
      final Optional<Duration> duration) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
    final String command = commandSupplier.get(); // validates workers/duration eagerly
    validateContainerRunning(container);
    installTools(container);
    killStressNg(container);
    exec(container, command, label + " stress");
    duration.ifPresentOrElse(
        d -> log.info("Started {} stress: {} workers for {}s", label, workers, d.toSeconds()),
        () -> log.info("Started {} stress: {} workers", label, workers));
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

  // ==================== Private: Validation ====================

  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }
  }
}
