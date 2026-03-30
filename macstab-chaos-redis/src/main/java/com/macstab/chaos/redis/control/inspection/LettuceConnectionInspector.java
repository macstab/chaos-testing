/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.api.Endpoint;
import com.macstab.chaos.redis.control.role.ContainerRole;
import com.macstab.chaos.redis.control.role.RoleResolver;

import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Inspects Lettuce connections to identify which Docker container they are connected to.
 *
 * <p><strong>Three-tier inspection strategy:</strong>
 *
 * <ul>
 *   <li>Tier 1 (Auto): Extracts host:port via {@link ConnectionEndpointExtractor}, matches
 *       containers
 *   <li>Tier 2 (Hint): Caller provides container reference directly
 *   <li>Tier 3 (Manual): Caller builds {@link ConnectionInfo} without live connection
 * </ul>
 *
 * <p>Endpoint extraction is delegated to {@link ConnectionEndpointExtractor}. Role resolution is
 * delegated to {@link RoleResolver}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class LettuceConnectionInspector implements ConnectionInspector {

  private final List<GenericContainer<?>> containers;
  private final RoleResolver roleResolver;

  /**
   * Creates a Lettuce connection inspector.
   *
   * @param containers list of all containers (master + replicas + sentinels)
   * @param roleResolver role resolver for container-to-role mapping
   * @throws NullPointerException if containers or roleResolver is null
   */
  public LettuceConnectionInspector(
      final List<GenericContainer<?>> containers, final RoleResolver roleResolver) {
    this.containers = List.copyOf(Objects.requireNonNull(containers, "containers"));
    this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver");
  }

  // ==================== Tier 1: Auto-Detection ====================

  @Override
  public ConnectionInfo inspect(final StatefulRedisConnection<?, ?> connection) {
    Objects.requireNonNull(connection, "connection");

    if (!connection.isOpen()) {
      throw new IllegalStateException(
          "Cannot inspect closed connection. Ensure connection.isOpen() == true.");
    }

    try {
      final Endpoint endpoint =
          ConnectionEndpointExtractor.extractEndpoint(connection)
              .orElseThrow(() -> buildExtractionFailureException(connection.toString()));

      final GenericContainer<?> container =
          findMatchingContainer(endpoint).orElseThrow(() -> buildNoMatchException(endpoint));

      final ContainerRole role = roleResolver.resolve(container);
      final boolean healthy = checkHealth(connection);
      final String info =
          String.format("%s (%s) - %s", role, endpoint, healthy ? "HEALTHY" : "UNHEALTHY");

      log.debug("Inspected connection (auto-detect): {}", info);
      return new ConnectionInfo(role, container, info, healthy);

    } catch (final Exception e) {
      log.error("Failed to inspect connection (auto-detect)", e);
      throw e instanceof IllegalStateException
          ? (IllegalStateException) e
          : new IllegalStateException("Connection inspection failed: " + e.getMessage(), e);
    }
  }

  // ==================== Tier 2: Explicit Hint ====================

  @Override
  public ConnectionInfo inspect(
      final StatefulRedisConnection<?, ?> connection, final GenericContainer<?> containerHint) {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(containerHint, "containerHint");

    if (!connection.isOpen()) {
      throw new IllegalStateException("Cannot inspect closed connection.");
    }
    if (!containerHint.isRunning()) {
      throw new IllegalStateException(
          "Container is not running: " + containerHint.getContainerId());
    }

    final ContainerRole role = roleResolver.resolve(containerHint);
    final boolean healthy = checkHealth(connection);
    final String info =
        String.format("%s (user-provided) - %s", role, healthy ? "HEALTHY" : "UNHEALTHY");

    log.debug("Inspected connection (explicit hint): {}", info);
    return new ConnectionInfo(role, containerHint, info, healthy);
  }

  // ==================== Tier 3: Manual ====================

  @Override
  public ConnectionInfo inspectManual(
      final GenericContainer<?> container, final String connectionDescription) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(connectionDescription, "connectionDescription");

    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running: " + container.getContainerId());
    }

    final ContainerRole role = roleResolver.resolve(container);
    final String full = String.format("%s (%s) - MANUAL", role, connectionDescription);
    log.debug("Inspected manually: {}", full);
    return new ConnectionInfo(role, container, full, true);
  }

  // ==================== Container Matching ====================

  private Optional<GenericContainer<?>> findMatchingContainer(final Endpoint endpoint) {
    return containers.stream()
        .filter(GenericContainer::isRunning)
        .filter(
            c -> endpoint.host().equals(c.getHost()) && endpoint.port() == c.getFirstMappedPort())
        .findFirst();
  }

  // ==================== Health Check ====================

  private boolean checkHealth(final StatefulRedisConnection<?, ?> connection) {
    try {
      return "PONG".equalsIgnoreCase(connection.sync().ping());
    } catch (final Exception e) {
      log.warn("Health check failed: {}", e.getMessage());
      return false;
    }
  }

  // ==================== Exception Builders ====================

  private IllegalStateException buildExtractionFailureException(final String connectionString) {
    return new IllegalStateException(
        "Cannot auto-detect container: failed to extract endpoint from connection.\n"
            + "Connection string: "
            + connectionString
            + "\n"
            + "Use inspect(connection, containerHint) or inspectManual() as fallback.");
  }

  private IllegalStateException buildNoMatchException(final Endpoint endpoint) {
    final StringBuilder sb =
        new StringBuilder()
            .append("No container found matching endpoint: ")
            .append(endpoint)
            .append("\n\n")
            .append("Available containers:\n");

    containers.stream()
        .filter(GenericContainer::isRunning)
        .forEach(
            c ->
                sb.append("  - ")
                    .append(roleResolver.resolve(c))
                    .append(" @ ")
                    .append(c.getHost())
                    .append(":")
                    .append(c.getFirstMappedPort())
                    .append("\n"));

    sb.append("\nWorkaround: Use inspect(connection, containerHint)");
    return new IllegalStateException(sb.toString());
  }
}
