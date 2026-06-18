/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Polls {@code /proc/[0-9]&#42;/comm} to detect process presence or absence inside a container.
 *
 * <p>Provides two symmetric operations:
 *
 * <ul>
 *   <li>{@link #waitUntilGone} — polls until a named process disappears or timeout expires
 *   <li>{@link #waitUntilStarted} — polls until a named process appears or timeout expires
 * </ul>
 *
 * <h2>Why /proc/comm instead of ps</h2>
 *
 * <p>{@code /proc/[0-9]&#42;/comm} is a kernel-provided interface available on all Linux containers
 * without any installed utilities. {@code ps} is not available on Alpine minimal images and BusyBox
 * {@code ps} does not support {@code aux}. The {@code /proc} approach has zero dependency on
 * installed tools.
 *
 * <h2>Zombie awareness in waitUntilStarted</h2>
 *
 * <p>{@link #waitUntilStarted} uses a simple {@code grep} — sufficient because a newly started
 * process will never be in zombie state. Zombie filtering is only needed in observability queries
 * ({@link CpuObservability#isStressed}, {@link CpuObservability#isThrottled}) where previously
 * killed processes may linger.
 *
 * <h2>Poll interval</h2>
 *
 * <p>The 10ms poll interval aligns with Docker {@code execInContainer} round-trip latency (5–15ms
 * on Moby/macOS, <5ms in DinD). With tini as init, processes are reaped within milliseconds — the
 * first poll after SIGKILL typically finds no process.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
final class ProcessPoller {

  /**
   * Poll interval in milliseconds.
   *
   * <p>10ms aligns with Docker exec round-trip latency. With tini as PID 1, processes are reaped in
   * <5ms after SIGKILL — the first poll after kill typically succeeds.
   */
  private static final long POLL_INTERVAL_MS = 10;

  /** Utility class — not instantiable. */
  private ProcessPoller() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Polls until the named process is no longer visible in {@code /proc/[0-9]&#42;/comm}.
   *
   * <p>Uses a simple {@code grep} (no zombie filter) — with tini as init, processes are fully
   * reaped ({@code /proc} entry removed) within milliseconds of SIGKILL.
   *
   * <p>If the timeout expires without the process disappearing, a warning is logged and the method
   * returns normally. The caller may verify state via the observability API.
   *
   * @param container running container
   * @param commName process comm name to wait for disappearance (prefix match)
   * @param timeoutMs maximum wait time in milliseconds
   */
  static void waitUntilGone(
      final GenericContainer<?> container, final String commName, final long timeoutMs) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(commName, "commName must not be null");

    final String check =
        String.format("grep -rl '^%s' /proc/[0-9]*/comm 2>/dev/null | grep -q .", commName);
    poll(container, check, false, commName, timeoutMs, "gone");
  }

  /**
   * Polls until the named process appears in {@code /proc/[0-9]&#42;/comm}.
   *
   * <p>Background processes ({@code cmd &}) return exit 0 from the shell immediately but may not
   * yet be visible in {@code /proc} when the exec returns. This method bridges that gap
   * event-driven rather than with a blind sleep.
   *
   * <p>Uses exact-match grep — the comm name of a backgrounded process is known exactly.
   *
   * @param container running container
   * @param commName exact process comm name to wait for appearance
   * @param timeoutMs maximum wait time in milliseconds
   */
  static void waitUntilStarted(
      final GenericContainer<?> container, final String commName, final long timeoutMs) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(commName, "commName must not be null");

    final String check =
        String.format("grep -rl '^%s$' /proc/[0-9]*/comm 2>/dev/null | grep -q .", commName);
    poll(container, check, true, commName, timeoutMs, "started");
  }

  // ==================== Private ====================

  /**
   * Core polling loop — runs {@code checkCommand} at {@link #POLL_INTERVAL_MS} intervals.
   *
   * @param container running container
   * @param checkCommand shell command: exits 0 if process present, non-zero if absent
   * @param exitWhenFound {@code true} = stop when exit 0 (waitUntilStarted); {@code false} = stop
   *     when exit non-0 (waitUntilGone)
   * @param commName process name for logging
   * @param timeoutMs maximum total wait in milliseconds
   * @param logSuffix label for timeout warning ("gone" or "started")
   */
  private static void poll(
      final GenericContainer<?> container,
      final String checkCommand,
      final boolean exitWhenFound,
      final String commName,
      final long timeoutMs,
      final String logSuffix) {
    final long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        final var result = container.execInContainer(Shell.SH, Shell.FLAG_C, checkCommand);
        final boolean found = result.getExitCode() == 0;
        if (found == exitWhenFound) {
          return;
        }
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (final Exception e) {
        return; // container stopped or exec failed — treat as condition met
      }
    }
    log.warn("ProcessPoller: '{}' did not become {} within {}ms", commName, logSuffix, timeoutMs);
  }
}
