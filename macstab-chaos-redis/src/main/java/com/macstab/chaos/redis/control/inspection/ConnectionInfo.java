/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.control.role.ContainerRole;

/**
 * Immutable record representing the result of connection inspection.
 *
 * <p><strong>Contains:</strong>
 *
 * <ul>
 *   <li>{@link #role} - Container role (MASTER, REPLICA_0, etc.)
 *   <li>{@link #container} - GenericContainer instance
 *   <li>{@link #connectionInfo} - Human-readable connection description
 *   <li>{@link #healthy} - Whether connection is healthy
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Records are immutable and inherently thread-safe.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Inspect connection
 * ConnectionInfo info = inspector.inspect(connection);
 *
 * // Check role
 * if (info.role().isMaster()) {
 *     System.out.println("Connected to master");
 * }
 *
 * // Get container for lifecycle control
 * GenericContainer<?> container = info.container();
 * controller.restart(container);
 *
 * // Verify health
 * if (!info.healthy()) {
 *     System.err.println("Unhealthy connection: " + info.connectionInfo());
 * }
 * }</pre>
 *
 * @param role container role (never null)
 * @param container GenericContainer instance (never null)
 * @param connectionInfo human-readable connection description (never null)
 * @param healthy whether connection is healthy
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public record ConnectionInfo(
    ContainerRole role, GenericContainer<?> container, String connectionInfo, boolean healthy) {

  /**
   * Compact constructor with validation.
   *
   * @throws NullPointerException if role, container, or connectionInfo is null
   */
  public ConnectionInfo {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(connectionInfo, "connectionInfo");
  }

  /**
   * Creates ConnectionInfo for a healthy connection.
   *
   * @param role container role (never null)
   * @param container GenericContainer instance (never null)
   * @param connectionInfo connection description (never null)
   * @return ConnectionInfo with healthy=true
   * @throws NullPointerException if any parameter is null
   */
  public static ConnectionInfo healthy(
      final ContainerRole role, final GenericContainer<?> container, final String connectionInfo) {
    return new ConnectionInfo(role, container, connectionInfo, true);
  }

  /**
   * Creates ConnectionInfo for an unhealthy connection.
   *
   * @param role container role (never null)
   * @param container GenericContainer instance (never null)
   * @param connectionInfo connection description (never null)
   * @return ConnectionInfo with healthy=false
   * @throws NullPointerException if any parameter is null
   */
  public static ConnectionInfo unhealthy(
      final ContainerRole role, final GenericContainer<?> container, final String connectionInfo) {
    return new ConnectionInfo(role, container, connectionInfo, false);
  }

  /**
   * Creates ConnectionInfo for an unknown/failed connection.
   *
   * @param container GenericContainer instance (never null)
   * @param reason failure reason (never null)
   * @return ConnectionInfo with role=UNKNOWN, healthy=false
   * @throws NullPointerException if any parameter is null
   */
  public static ConnectionInfo unknown(final GenericContainer<?> container, final String reason) {
    Objects.requireNonNull(reason, "reason");
    return new ConnectionInfo(ContainerRole.UNKNOWN, container, reason, false);
  }
}
