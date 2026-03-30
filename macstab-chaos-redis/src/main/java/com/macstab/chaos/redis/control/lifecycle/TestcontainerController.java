/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.lifecycle;

import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ContainerOperationException;
import com.macstab.chaos.core.util.ContainerIdFormatter;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Container lifecycle controller using Testcontainers and Docker API.
 *
 * <p><strong>Operations:</strong> restart (graceful stop+start), kill (SIGKILL), pause (cgroup
 * freeze), resume (unfreeze), waitForReady (PING poll).
 *
 * <p><strong>Note:</strong> {@code pause} uses Docker's {@code freeze} which suspends all processes
 * in the container's cgroup. The container remains in the Docker network but stops processing
 * commands.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class TestcontainerController implements ContainerController {
  private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration RETRY_INTERVAL = Duration.ofMillis(500);

  @Override
  public void restart(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    final String containerId = container.getContainerId();
    log.info("🔄 Restarting container: {}", ContainerIdFormatter.truncate(containerId));

    try {
      // Graceful stop
      final long stopStart = System.currentTimeMillis();
      container.stop();
      log.debug(
          "✓ Container stopped: {} ({}ms)",
          ContainerIdFormatter.truncate(containerId),
          System.currentTimeMillis() - stopStart);

      // Start
      final long startStart = System.currentTimeMillis();
      container.start();
      log.debug(
          "✓ Container started: {} ({}ms)",
          ContainerIdFormatter.truncate(containerId),
          System.currentTimeMillis() - startStart);

      // Wait for readiness
      waitForReady(container, DEFAULT_READINESS_TIMEOUT);
      log.info(
          "✓ Container restarted successfully: {}", ContainerIdFormatter.truncate(containerId));

    } catch (final Exception e) {
      log.error("✗ Failed to restart container: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new ContainerOperationException(
          "restart", containerId, "Container failed to restart properly", e);
    }
  }

  @Override
  public void kill(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    final String containerId = container.getContainerId();
    log.info("🔥 Killing container: {}", ContainerIdFormatter.truncate(containerId));

    try {
      // Docker kill (SIGKILL) - immediate termination
      container.getDockerClient().killContainerCmd(containerId).exec();
      log.info("✓ Container killed: {}", ContainerIdFormatter.truncate(containerId));

    } catch (final Exception e) {
      log.error("✗ Failed to kill container: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new ContainerOperationException(
          "kill", containerId, "Container kill operation failed", e);
    }
  }

  @Override
  public void pause(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    final String containerId = container.getContainerId();
    log.info("⏸ Pausing container: {}", ContainerIdFormatter.truncate(containerId));

    try {
      // Docker pause - freeze all processes
      container.getDockerClient().pauseContainerCmd(containerId).exec();
      log.info("✓ Container paused: {}", ContainerIdFormatter.truncate(containerId));

    } catch (final Exception e) {
      log.error("✗ Failed to pause container: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new ContainerOperationException(
          "pause", containerId, "Container pause operation failed", e);
    }
  }

  @Override
  public void resume(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    final String containerId = container.getContainerId();
    log.info("▶ Resuming container: {}", ContainerIdFormatter.truncate(containerId));

    try {
      // Docker unpause - unfreeze processes
      container.getDockerClient().unpauseContainerCmd(containerId).exec();
      log.info("✓ Container resumed: {}", ContainerIdFormatter.truncate(containerId));

    } catch (final Exception e) {
      log.error("✗ Failed to resume container: {}", ContainerIdFormatter.truncate(containerId), e);
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

    log.debug("Waiting for container readiness: {}", container.getContainerId());

    final long startTime = System.currentTimeMillis();
    final long timeoutMillis = timeout.toMillis();

    while (System.currentTimeMillis() - startTime < timeoutMillis) {
      if (isPingSuccessful(container)) {
        log.debug("Container ready: {}", container.getContainerId());
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

    } catch (final Exception e) {
      log.trace("PING failed for container: {}", container.getContainerId(), e);
      return false;
    }
  }
}
