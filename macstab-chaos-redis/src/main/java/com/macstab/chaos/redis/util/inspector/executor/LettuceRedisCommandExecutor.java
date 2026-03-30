/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.executor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link RedisCommandExecutor} backed by a Lettuce {@link RedisCommands} connection.
 *
 * <p>Preferred when the caller already manages a Lettuce connection — avoids the per-call process
 * overhead of {@link ShellRedisCommandExecutor} and reuses the existing network connection.
 *
 * <p>Supports the command subset used by inspector tools:
 * <ul>
 *   <li>{@code SLOWLOG RESET}
 *   <li>{@code SLOWLOG GET [count]}
 *   <li>{@code CLIENT LIST}
 *   <li>{@code INFO [section]}
 *   <li>{@code SET key value}
 *   <li>{@code GET key}
 *   <li>{@code DEL key}
 * </ul>
 *
 * <p>For {@code SLOWLOG GET}, raw output is returned as a formatted string representation of the
 * nested list — inspect via {@link com.macstab.chaos.redis.util.inspector.SlowCommandDetector}
 * which uses typed Lettuce methods directly for SLOWLOG parsing.
 *
 * <p><strong>Note on SLOWLOG GET:</strong> Because Lettuce returns SLOWLOG as {@code List<Object>}
 * (not a string), this executor does NOT support {@code SLOWLOG GET} via the string-based
 * {@link #execute(String)} interface. {@link com.macstab.chaos.redis.util.inspector.SlowCommandDetector}
 * uses its own typed Lettuce path for SLOWLOG parsing. This executor covers the remaining commands.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * RedisCommandExecutor executor = new LettuceRedisCommandExecutor(redisCommands);
 * SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);
 * ConnectionLeakTracker tracker = new ConnectionLeakTracker(executor);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class LettuceRedisCommandExecutor implements RedisCommandExecutor, AutoCloseable {

  private final RedisCommands<String, String> redisCommands;
  private final RedisClient ownedClient;             // non-null only when we created the client
  private final StatefulRedisConnection<String, String> ownedConnection; // same

  /**
   * Creates a Lettuce-backed executor using an existing {@link RedisCommands} connection.
   *
   * <p>The caller retains ownership of the connection lifecycle — {@link #close()} is a no-op.
   *
   * @param redisCommands Lettuce sync commands — must not be null
   * @throws NullPointerException if redisCommands is null
   */
  public LettuceRedisCommandExecutor(final RedisCommands<String, String> redisCommands) {
    this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
    this.ownedClient = null;
    this.ownedConnection = null;
  }

  /**
   * Creates a Lettuce-backed executor by connecting to a Redis instance at the given host/port.
   *
   * <p>This executor owns the created {@link RedisClient} and connection. Call {@link #close()}
   * when done (or use try-with-resources). Typically called by inspector tool factory methods.
   *
   * @param host Redis host — must not be null
   * @param port Redis mapped port (1–65535)
   * @throws NullPointerException     if host is null
   * @throws IllegalArgumentException if port is out of range
   */
  public LettuceRedisCommandExecutor(final String host, final int port) {
    Objects.requireNonNull(host, "host");
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be in range 1-65535 but was: " + port);
    }
    final RedisURI uri = RedisURI.builder().withHost(host).withPort(port).build();
    this.ownedClient = RedisClient.create(uri);
    this.ownedConnection = ownedClient.connect();
    this.redisCommands = ownedConnection.sync();
  }

  /**
   * Closes the owned {@link RedisClient} and connection, if this executor created them.
   *
   * <p>No-op if this executor was constructed with an externally-managed {@link RedisCommands}.
   */
  @Override
  public void close() {
    if (ownedConnection != null) {
      try {
        ownedConnection.close();
      } catch (final Exception e) {
        log.debug("Error closing owned Lettuce connection", e);
      }
    }
    if (ownedClient != null) {
      try {
        ownedClient.shutdown();
      } catch (final Exception e) {
        log.debug("Error shutting down owned Lettuce client", e);
      }
    }
  }

  /**
   * Dispatches the Redis command string to the appropriate Lettuce typed method.
   *
   * @param redisCommand Redis command string (e.g., {@code "CLIENT LIST"}, {@code "INFO memory"})
   * @return string output (result of the Lettuce call, formatted as text)
   * @throws RedisCommandExecutionException if the command is unsupported or execution fails
   */
  @Override
  public String execute(final String redisCommand) {
    Objects.requireNonNull(redisCommand, "redisCommand");

    final String normalized = redisCommand.trim();
    final String upper = normalized.toUpperCase(Locale.ROOT);
    log.trace("Dispatching Lettuce command: {}", normalized);

    try {
      if (upper.equals("CLIENT LIST")) {
        return Objects.toString(redisCommands.clientList(), "");
      }
      if (upper.startsWith("INFO")) {
        final String section = normalized.length() > 4 ? normalized.substring(5).trim() : "all";
        return Objects.toString(redisCommands.info(section), "");
      }
      if (upper.equals("SLOWLOG RESET")) {
        redisCommands.slowlogReset();
        return "OK";
      }
      if (upper.startsWith("SET ")) {
        final String[] parts = normalized.split(" ", 3);
        if (parts.length == 3) {
          return Objects.toString(redisCommands.set(parts[1], parts[2]), "");
        }
      }
      if (upper.startsWith("GET ")) {
        final String key = normalized.substring(4).trim();
        return Objects.toString(redisCommands.get(key), "");
      }
      if (upper.startsWith("DEL ")) {
        final String key = normalized.substring(4).trim();
        return Objects.toString(redisCommands.del(key), "");
      }
      throw new RedisCommandExecutionException(
          "Unsupported command for LettuceRedisCommandExecutor: " + redisCommand
          + ". Use SlowCommandDetector.forCommands() for SLOWLOG operations.");
    } catch (final RedisCommandExecutionException e) {
      throw e;
    } catch (final Exception e) {
      throw new RedisCommandExecutionException(
          "Lettuce execution failed for command: " + redisCommand, e);
    }
  }
}
