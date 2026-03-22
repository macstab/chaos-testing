/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Contract for inspecting Redis connections to determine which container they're using.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>✅ Identifies container role (MASTER, REPLICA_0, etc.)
 *   <li>✅ Returns GenericContainer instance for lifecycle control
 *   <li>✅ Provides connection health status
 *   <li>✅ Thread-safe implementations required
 *   <li>✅ 3-tier inspection: auto-detect, explicit hint, manual
 * </ul>
 *
 * <p><strong>3-Tier Inspection Strategy:</strong>
 *
 * <table border="1">
 *   <caption>Inspection Methods</caption>
 *   <tr><th>Tier</th><th>Method</th><th>Use Case</th><th>Reliability</th></tr>
 *   <tr><td>1</td><td>inspect(connection)</td><td>Auto-detect container</td><td>90%</td></tr>
 *   <tr><td>2</td><td>inspect(connection, hint)</td><td>Explicit container</td><td>100%</td></tr>
 *   <tr><td>3</td><td>inspectManual(container, desc)</td><td>Full control</td><td>100%</td></tr>
 * </table>
 *
 * <p><strong>Workflow Example:</strong>
 *
 * <pre>{@code
 * // Tier 1: Auto-detection (easiest, works 90% of time)
 * ConnectionInfo info = inspector.inspect(connection);
 *
 * // Tier 2: Explicit hint (if auto-detection fails)
 * GenericContainer<?> replica = cluster.getReplicaContainers().get(0);
 * ConnectionInfo info = inspector.inspect(connection, replica);
 *
 * // Tier 3: Manual (full control)
 * ConnectionInfo info = inspector.inspectManual(container, "Replica 0");
 * }</pre>
 *
 * <p><strong>Implementation Note:</strong> Implementations must be thread-safe. Multiple threads
 * can inspect connections concurrently.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see LettuceConnectionInspector
 * @see ConnectionInfo
 */
public interface ConnectionInspector {

  /**
   * Inspects a Lettuce connection to determine which container it's using (auto-detection).
   *
   * <p><strong>Tier 1: Auto-Detection</strong>
   *
   * <p>Attempts to extract remote endpoint from connection and match against containers.
   *
   * <p><strong>Algorithm:</strong>
   *
   * <ol>
   *   <li>Extract remote endpoint (host:port) from connection (via toString parsing)
   *   <li>Match against running containers
   *   <li>Resolve container role (MASTER, REPLICA_0, etc.)
   *   <li>Check connection health (PING command)
   *   <li>Return ConnectionInfo with results
   * </ol>
   *
   * <p><strong>Fallback:</strong> If auto-detection fails, use {@link
   * #inspect(StatefulRedisConnection, org.testcontainers.containers.GenericContainer)}.
   *
   * @param connection Lettuce connection to inspect (never null)
   * @return connection info with role, container, and health status (never null)
   * @throws NullPointerException if connection is null
   * @throws IllegalStateException if connection is closed, cannot extract endpoint, or no matching
   *     container
   */
  ConnectionInfo inspect(StatefulRedisConnection<?, ?> connection);

  /**
   * Inspects a Lettuce connection with explicit container hint (100% reliable).
   *
   * <p><strong>Tier 2: Explicit Hint</strong>
   *
   * <p>Bypasses endpoint extraction by using user-provided container hint.
   *
   * <p><strong>Use When:</strong>
   *
   * <ul>
   *   <li>Auto-detection fails (Lettuce toString format changed)
   *   <li>You already know which container connection is using
   *   <li>You need guaranteed reliability (no parsing)
   * </ul>
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * GenericContainer<?> replica0 = cluster.getReplicaContainers().get(0);
   * ConnectionInfo info = inspector.inspect(connection, replica0);
   * }</pre>
   *
   * @param connection Lettuce connection to inspect (never null)
   * @param containerHint the container this connection is using (never null)
   * @return connection info with role, container, and health status (never null)
   * @throws NullPointerException if connection or containerHint is null
   * @throws IllegalStateException if connection is closed or container is not running
   * @since 2.0
   */
  ConnectionInfo inspect(
      StatefulRedisConnection<?, ?> connection,
      org.testcontainers.containers.GenericContainer<?> containerHint);

  /**
   * Creates connection info manually without inspecting connection (full control).
   *
   * <p><strong>Tier 3: Manual</strong>
   *
   * <p>Fully manual construction for edge cases where no connection exists or inspection isn't
   * needed.
   *
   * <p><strong>Use When:</strong>
   *
   * <ul>
   *   <li>No active connection available
   *   <li>Pre-computing connection info for planning
   *   <li>Testing/mocking scenarios
   * </ul>
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * GenericContainer<?> master = cluster.getMasterContainer();
   * ConnectionInfo info = inspector.inspectManual(master, "Master node");
   * }</pre>
   *
   * @param container the container to create info for (never null)
   * @param connectionDescription human-readable description (never null)
   * @return connection info with role, container, and description (never null)
   * @throws NullPointerException if container or connectionDescription is null
   * @throws IllegalStateException if container is not running
   * @since 2.0
   */
  ConnectionInfo inspectManual(
      org.testcontainers.containers.GenericContainer<?> container, String connectionDescription);
}
