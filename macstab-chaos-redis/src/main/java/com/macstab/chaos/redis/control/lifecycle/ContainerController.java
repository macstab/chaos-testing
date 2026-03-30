/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.lifecycle;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Contract for container lifecycle control operations.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>✅ Restart containers (stop + start)
 *   <li>✅ Kill containers (immediate termination)
 *   <li>✅ Pause containers (freeze execution)
 *   <li>✅ Resume containers (unfreeze)
 *   <li>✅ Wait for container readiness after operations
 * </ul>
 *
 * <p><strong>Use Cases:</strong>
 *
 * <table border="1">
 *   <caption>Lifecycle Control Use Cases</caption>
 *   <tr><th>Operation</th><th>Use Case</th></tr>
 *   <tr><td>restart()</td><td>Test failover, connection recovery</td></tr>
 *   <tr><td>kill()</td><td>Simulate hard crashes</td></tr>
 *   <tr><td>pause()</td><td>Simulate network partitions</td></tr>
 *   <tr><td>resume()</td><td>Restore connectivity after partition</td></tr>
 * </table>
 *
 * <p><strong>Workflow Example:</strong>
 *
 * <pre>{@code
 * // 1. Inspect connection
 * ConnectionInfo info = inspector.inspect(connection);
 *
 * // 2. Restart replica
 * controller.restart(info.container());
 *
 * // 3. Wait for reconnection
 * Thread.sleep(1000);
 *
 * // 4. Verify reconnected to different replica
 * ConnectionInfo newInfo = inspector.inspect(connection);
 * assertThat(newInfo.role()).isNotEqualTo(info.role());
 * }</pre>
 *
 * <p><strong>Implementation Note:</strong> Implementations must be thread-safe. Multiple threads
 * can control containers concurrently.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 * @see TestcontainerController
 */
public interface ContainerController {

  /**
   * Restarts a container (graceful stop + start).
   *
   * <p><strong>Workflow:</strong>
   *
   * <ol>
   *   <li>Stop container (sends SIGTERM)
   *   <li>Wait for graceful shutdown (up to 10 seconds)
   *   <li>Start container
   *   <li>Wait for readiness (PING succeeds)
   * </ol>
   *
   * @param container the container to restart (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if restart fails
   */
  void restart(GenericContainer<?> container);

  /**
   * Kills a container (immediate termination, SIGKILL).
   *
   * <p><strong>Use Case:</strong> Simulate hard crashes without graceful shutdown.
   *
   * @param container the container to kill (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if kill fails
   */
  void kill(GenericContainer<?> container);

  /**
   * Pauses a container (freezes all processes).
   *
   * <p><strong>Use Case:</strong> Simulate network partitions or slow nodes.
   *
   * @param container the container to pause (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if pause fails
   */
  void pause(GenericContainer<?> container);

  /**
   * Resumes a paused container (unfreezes processes).
   *
   * @param container the container to resume (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if resume fails or container not paused
   */
  void resume(GenericContainer<?> container);

  /**
   * Waits for a container to become ready (PING succeeds).
   *
   * <p><strong>Default Timeout:</strong> 30 seconds
   *
   * @param container the container to wait for (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if container doesn't become ready within timeout
   */
  void waitForReady(GenericContainer<?> container);

  /**
   * Waits for a container to become ready with custom timeout.
   *
   * @param container the container to wait for (never null)
   * @param timeout maximum wait time (never null)
   * @throws NullPointerException if container or timeout is null
   * @throws IllegalStateException if container doesn't become ready within timeout
   */
  void waitForReady(GenericContainer<?> container, Duration timeout);
}
