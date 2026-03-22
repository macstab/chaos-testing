/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.lifecycle;

import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ContainerOperationException;
import com.macstab.chaos.core.util.ContainerIdFormatter;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Testcontainers-specific lifecycle controller.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>✅ Restart: Graceful stop (SIGTERM) + start + readiness wait
 *   <li>✅ Kill: Immediate termination (SIGKILL)
 *   <li>✅ Pause: Freeze container processes (Docker pause)
 *   <li>✅ Resume: Unfreeze container processes (Docker unpause)
 *   <li>✅ Readiness: PING command validation with retries
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Immutable singleton. Thread-safe for concurrent use.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Create controller
 * ContainerController controller = new TestcontainerController();
 *
 * // Restart replica
 * controller.restart(replicaContainer);
 *
 * // Kill master (simulate hard crash)
 * controller.kill(masterContainer);
 *
 * // Pause container (simulate network partition)
 * controller.pause(sentinelContainer);
 * Thread.sleep(5000);
 * controller.resume(sentinelContainer);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class TestcontainerController implements ContainerController {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestcontainerController.class);

  private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration RETRY_INTERVAL = Duration.ofMillis(500);

  @Override
  public void restart(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    final String containerId = container.getContainerId();
    LOGGER.info("🔄 Restarting container: {}", ContainerIdFormatter.truncate(containerId));

    try {
      // Graceful stop
      final long stopStart = System.currentTimeMillis();
      container.stop();
      LOGGER.debug(
          "✓ Container stopped: {} ({}ms)",
          ContainerIdFormatter.truncate(containerId),
          System.currentTimeMillis() - stopStart);

      // Start
      final long startStart = System.currentTimeMillis();
      container.start();
      LOGGER.debug(
          "✓ Container started: {} ({}ms)",
          ContainerIdFormatter.truncate(containerId),
          System.currentTimeMillis() - startStart);

      // Wait for readiness
      waitForReady(container, DEFAULT_READINESS_TIMEOUT);
      LOGGER.info(
          "✓ Container restarted successfully: {}", ContainerIdFormatter.truncate(containerId));

    } catch (Exception e) {
      LOGGER.error(
          "✗ Failed to restart container: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new ContainerOperationException(
          "restart", containerId, "Container failed to restart properly", e);
    }
  }

  @Override
  public void kill(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    final String containerId = container.getContainerId();
    LOGGER.info("🔥 Killing container: {}", ContainerIdFormatter.truncate(containerId));

    try {
      // Docker kill (SIGKILL) - immediate termination
      container.getDockerClient().killContainerCmd(containerId).exec();
      LOGGER.info("✓ Container killed: {}", ContainerIdFormatter.truncate(containerId));

    } catch (Exception e) {
      LOGGER.error("✗ Failed to kill container: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new ContainerOperationException(
          "kill", containerId, "Container kill operation failed", e);
    }
  }

  @Override
  public void pause(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    final String containerId = container.getContainerId();
    LOGGER.info("⏸ Pausing container: {}", ContainerIdFormatter.truncate(containerId));

    try {
      // Docker pause - freeze all processes
      container.getDockerClient().pauseContainerCmd(containerId).exec();
      LOGGER.info("✓ Container paused: {}", ContainerIdFormatter.truncate(containerId));

    } catch (Exception e) {
      LOGGER.error(
          "✗ Failed to pause container: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new ContainerOperationException(
          "pause", containerId, "Container pause operation failed", e);
    }
  }

  @Override
  public void resume(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    final String containerId = container.getContainerId();
    LOGGER.info("▶ Resuming container: {}", ContainerIdFormatter.truncate(containerId));

    try {
      // Docker unpause - unfreeze processes
      container.getDockerClient().unpauseContainerCmd(containerId).exec();
      LOGGER.info("✓ Container resumed: {}", ContainerIdFormatter.truncate(containerId));

    } catch (Exception e) {
      LOGGER.error(
          "✗ Failed to resume container: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new ContainerOperationException(
          "resume", containerId, "Container resume operation failed", e);
    }
  }

  @Override
  public void waitForReady(final GenericContainer<?> container) {
    waitForReady(container, DEFAULT_READINESS_TIMEOUT);
  }

  @Override
  public void waitForReady(final GenericContainer<?> container, final Duration timeout) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(timeout, "timeout");

    LOGGER.debug("Waiting for container readiness: {}", container.getContainerId());

    final long startTime = System.currentTimeMillis();
    final long timeoutMillis = timeout.toMillis();

    while (System.currentTimeMillis() - startTime < timeoutMillis) {
      if (isPingSuccessful(container)) {
        LOGGER.debug("Container ready: {}", container.getContainerId());
        return;
      }

      try {
        Thread.sleep(RETRY_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ContainerOperationException(
            "waitForReady",
            container.getContainerId(),
            "Interrupted while waiting for readiness",
            e);
      }
    }

    throw new ContainerOperationException(
        "waitForReady",
        container.getContainerId(),
        "Container did not become ready within " + timeout);
  }

  // ==================== Internal Health Check ====================

  /**
   * Checks if Redis PING succeeds.
   *
   * @param container the container to ping
   * @return true if PING returns PONG
   */
  private boolean isPingSuccessful(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      return false;
    }

    try {
      final String host = container.getHost();
      final int port = container.getFirstMappedPort();

      final RedisURI uri = RedisURI.builder().withHost(host).withPort(port).build();

      try (final RedisClient client = RedisClient.create(uri);
          final StatefulRedisConnection<String, String> conn = client.connect()) {

        final String pong = conn.sync().ping();
        return "PONG".equalsIgnoreCase(pong);
      }

    } catch (Exception e) {
      LOGGER.trace("PING failed for container: {}", container.getContainerId(), e);
      return false;
    }
  }
}
