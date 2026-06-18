/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.executor;

import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link RedisCommandExecutor} that executes commands via {@code redis-cli} inside a Testcontainers
 * container using {@link Shell#exec(GenericContainer, String)}.
 *
 * <p>No Lettuce dependency required. Works with any Redis container topology including
 * Docker-in-Docker, network-isolated containers, and Podman-managed containers.
 *
 * <p>The Redis port is configurable. Use the default constant {@link #DEFAULT_REDIS_PORT} (6379)
 * for standard Redis instances, or pass a custom port for non-standard deployments.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * RedisCommandExecutor executor = new ShellRedisCommandExecutor(container);
 * String output = executor.execute("INFO memory");
 *
 * // Custom port
 * RedisCommandExecutor executor = new ShellRedisCommandExecutor(container, 6380);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class ShellRedisCommandExecutor implements RedisCommandExecutor {

  /**
   * Default Redis port used when no explicit port is provided.
   *
   * <p>Intentionally redeclared here (rather than referencing {@link
   * com.macstab.chaos.redis.command.RedisCommandBuilder#DEFAULT_REDIS_PORT}) to keep the {@code
   * executor} package free of dependencies on the {@code command} package.
   */
  public static final int DEFAULT_REDIS_PORT = 6379;

  private final GenericContainer<?> container;
  private final int port;

  /**
   * Creates an executor targeting port {@value #DEFAULT_REDIS_PORT}.
   *
   * @param container Redis container — must not be null and must be running
   * @throws NullPointerException if container is null
   */
  public ShellRedisCommandExecutor(final GenericContainer<?> container) {
    this(container, DEFAULT_REDIS_PORT);
  }

  /**
   * Creates an executor targeting a custom port.
   *
   * @param container Redis container — must not be null and must be running
   * @param port Redis port inside the container (e.g., 6379, 6380, 26379)
   * @throws NullPointerException if container is null
   * @throws IllegalArgumentException if port is not in range 1–65535
   */
  public ShellRedisCommandExecutor(final GenericContainer<?> container, final int port) {
    this.container = Objects.requireNonNull(container, "container");
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be in range 1-65535 but was: " + port);
    }
    this.port = port;
  }

  /**
   * Executes a Redis command string via {@code redis-cli -p PORT <command>} inside the container.
   *
   * <p>The command string must NOT include the {@code redis-cli -p PORT} prefix — only the Redis
   * command itself (e.g., {@code "SLOWLOG RESET"}, {@code "INFO memory"}, {@code "CLIENT LIST"}).
   *
   * @param redisCommand Redis command to execute — must not be null
   * @return stdout output from redis-cli (never null, may be empty)
   * @throws RedisCommandExecutionException if execution fails or exits with non-zero status
   */
  @Override
  public String execute(final String redisCommand) {
    Objects.requireNonNull(redisCommand, "redisCommand");

    final String cliCommand = String.format("redis-cli -p %d %s", port, redisCommand);
    log.trace("Executing shell command in container: {}", cliCommand);

    try {
      final ExecResult result = Shell.exec(container, cliCommand);
      if (result.getExitCode() != 0) {
        throw new RedisCommandExecutionException(
            String.format(
                "redis-cli command failed with exit code %d: command=%s, stderr=%s",
                result.getExitCode(), redisCommand, result.getStderr()));
      }
      final String output = result.getStdout();
      return output == null ? "" : output;
    } catch (final RedisCommandExecutionException e) {
      throw e;
    } catch (final Exception e) {
      throw new RedisCommandExecutionException(
          "Failed to execute redis-cli command in container: " + redisCommand, e);
    }
  }

  /**
   * Returns the Redis port this executor targets.
   *
   * @return port number
   */
  public int getPort() {
    return port;
  }
}
