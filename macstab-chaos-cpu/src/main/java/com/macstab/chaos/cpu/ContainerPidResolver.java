/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import java.util.Objects;
import java.util.Set;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the main application PID inside a container, transparent to init systems.
 *
 * <h2>Problem</h2>
 *
 * <p>CPU chaos operations (throttle, affinity pinning, priority) must target the main application
 * process. Without an init system, the application is PID 1. With {@code --init}, PID 1 is the init
 * system (tini, docker-init, dumb-init) and the application runs as its first child.
 *
 * <h2>Detection Algorithm</h2>
 *
 * <ol>
 *   <li>Check label {@code macstab.chaos.pid.main} — return cached value immediately if present
 *   <li>Read {@code /proc/1/comm} — if it matches a known init binary name, find the first child
 *   <li>First child detection: scan {@code /proc/[0-9]&#42;/status} for {@code PPid: 1}
 *   <li>If any step fails or returns blank — fall back to PID 1
 * </ol>
 *
 * <h2>Caching</h2>
 *
 * <p>The resolved PID is stored via {@link GenericContainer#withLabel} under key {@code
 * macstab.chaos.pid.main}. This is a pure in-JVM operation — no Docker API call. Subsequent calls
 * on the same container return the cached value from a HashMap lookup.
 *
 * <h2>User Override</h2>
 *
 * <pre>{@code
 * container.withLabel("macstab.chaos.pid.main", "42");
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
final class ContainerPidResolver {

  /** Label key for caching or overriding the resolved main application PID. */
  static final String LABEL_MAIN_PID = "macstab.chaos.pid.main";

  /**
   * Known init process comm names that are not the main application.
   *
   * <p>These are the actual comm names read from {@code /proc/1/comm}:
   *
   * <ul>
   *   <li>{@code docker-init} -- Docker's built-in {@code --init} flag (wraps tini internally)
   *   <li>{@code tini} -- standalone tini binary used in custom Dockerfiles
   *   <li>{@code dumb-init} -- Yelp dumb-init
   *   <li>{@code s6-svscan} -- s6 supervision tree root
   *   <li>{@code s6-supervise} -- s6 service supervisor
   * </ul>
   */
  private static final Set<String> KNOWN_INIT_NAMES =
      Set.of("docker-init", "tini", "dumb-init", "s6-svscan", "s6-supervise");

  /**
   * Shell command to find the first direct child of PID 1.
   *
   * <p>Scans {@code /proc/[0-9]&#42;/status} for {@code PPid: 1}. Uses {@code while IFS= read -r}
   * for POSIX-safe line processing (no word splitting).
   */
  private static final String FIND_FIRST_CHILD_OF_INIT =
      "grep -rl '^PPid:[[:space:]]*1$' /proc/[0-9]*/status 2>/dev/null"
          + " | while IFS= read -r f; do"
          + " grep '^Pid:' \"$f\" | awk '{print $2}'; break;"
          + " done";

  /** Utility class — not instantiable. */
  private ContainerPidResolver() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Resolves the main application PID for the given container.
   *
   * <p>Returns the cached value from the container label if already resolved. Otherwise performs
   * init detection and caches the result.
   *
   * @param container running container
   * @return main application PID (1 if detection fails)
   */
  static int resolve(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    final String cached = container.getLabels().get(LABEL_MAIN_PID);
    if (cached != null) {
      return Integer.parseInt(cached);
    }

    final int pid = detect(container);
    container.withLabel(LABEL_MAIN_PID, String.valueOf(pid));
    return pid;
  }

  // ==================== Private ====================

  /**
   * Performs init detection and first-child lookup.
   *
   * @param container running container
   * @return resolved PID, defaults to 1 on any failure
   */
  private static int detect(final GenericContainer<?> container) {
    try {
      final var commResult = container.execInContainer(Shell.SH, Shell.FLAG_C, "cat /proc/1/comm");
      if (commResult.getExitCode() != 0) {
        return 1;
      }

      final String comm = commResult.getStdout().trim();
      if (!KNOWN_INIT_NAMES.contains(comm)) {
        return 1; // PID 1 is the application
      }

      // Init detected — find its first direct child
      final var childResult =
          container.execInContainer(Shell.SH, Shell.FLAG_C, FIND_FIRST_CHILD_OF_INIT);
      if (childResult.getExitCode() == 0 && !childResult.getStdout().isBlank()) {
        final int childPid = Integer.parseInt(childResult.getStdout().trim());
        log.info("Init process detected (comm={}), main application PID: {}", comm, childPid);
        return childPid;
      }

      return 1; // children file blank or empty — safe fallback
    } catch (final Exception e) {
      log.debug("PID detection failed, defaulting to PID 1: {}", e.getMessage());
      return 1;
    }
  }
}
