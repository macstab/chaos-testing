/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.Shell;
import com.macstab.chaos.redis.command.RedisCommandBuilder;
import com.macstab.chaos.redis.control.role.ContainerRole;

import lombok.extern.slf4j.Slf4j;

/**
 * Detects the {@link ContainerRole} of Redis containers by executing {@code redis-cli ROLE}.
 *
 * <p><strong>ROLE command output formats:</strong>
 *
 * <pre>
 * Master: "master" as first element
 * Replica: "slave" as first element
 * Sentinel: "sentinel" as first element (redis-sentinel port 26379)
 * </pre>
 *
 * <p>This class complements {@link ConnectionEndpointExtractor}: the extractor finds which
 * container a connection goes to; this detector finds what role that container plays.
 *
 * <p><strong>Thread Safety:</strong> Stateless — safe to use from multiple threads.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class RoleDetector {

  private RoleDetector() {
    throw new UnsupportedOperationException("Static utility class");
  }

  /**
   * Detects the role of a single container by executing {@code redis-cli ROLE}.
   *
   * <p>Returns {@link ContainerRole#UNKNOWN} if the container is not running or the command fails.
   *
   * @param container the container to inspect (must not be null)
   * @return detected role (never null)
   * @throws NullPointerException if container is null
   */
  public static ContainerRole detect(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    if (!container.isRunning()) {
      log.debug("Container not running, returning UNKNOWN: {}", container.getContainerId());
      return ContainerRole.UNKNOWN;
    }

    final int port = detectPort(container);

    try {
      final var result = Shell.exec(container, RedisCommandBuilder.buildRoleCommand(port));
      if (result.getExitCode() != 0) {
        log.warn(
            "redis-cli ROLE returned exit code {}: {}", result.getExitCode(), result.getStderr());
        return ContainerRole.UNKNOWN;
      }
      return parseRoleOutput(result.getStdout().trim());
    } catch (final Exception e) {
      log.warn(
          "Failed to detect role for container {}: {}", container.getContainerId(), e.getMessage());
      return ContainerRole.UNKNOWN;
    }
  }

  /**
   * Detects roles for multiple containers in order.
   *
   * @param containers list of containers to inspect (must not be null)
   * @return map of container → role (in input order)
   * @throws NullPointerException if containers is null
   */
  public static Map<GenericContainer<?>, ContainerRole> detectAll(
      final List<GenericContainer<?>> containers) {
    Objects.requireNonNull(containers, "containers");

    final Map<GenericContainer<?>, ContainerRole> result = new LinkedHashMap<>(containers.size());
    for (final GenericContainer<?> container : containers) {
      result.put(container, detect(container));
    }
    return result;
  }

  /**
   * Parses the output of {@code redis-cli ROLE}.
   *
   * <p>The first line contains the role name: "master", "slave", or "sentinel".
   *
   * @param output stdout from redis-cli ROLE (trimmed)
   * @return parsed role (UNKNOWN if unrecognized)
   */
  static ContainerRole parseRoleOutput(final String output) {
    if (output == null || output.isBlank()) {
      return ContainerRole.UNKNOWN;
    }

    final String firstLine = output.lines().findFirst().orElse("").trim().toLowerCase();

    if (firstLine.contains("master")) {
      return ContainerRole.MASTER;
    }
    if (firstLine.contains("slave") || firstLine.contains("replica")) {
      return ContainerRole.REPLICA_0;
    }
    if (firstLine.contains("sentinel")) {
      return ContainerRole.SENTINEL_0;
    }

    log.debug("Unrecognized ROLE output first line: '{}'", firstLine);
    return ContainerRole.UNKNOWN;
  }

  /**
   * Determines the Redis port to use for the ROLE command.
   *
   * <p>Sentinel containers expose port 26379; regular Redis containers expose port 6379.
   *
   * @param container the container
   * @return port to use
   */
  private static int detectPort(final GenericContainer<?> container) {
    try {
      if (container.getExposedPorts().contains(26379)) {
        return 26379;
      }
    } catch (final Exception ignored) {
      // Ignore — use default port
    }
    return 6379;
  }
}
