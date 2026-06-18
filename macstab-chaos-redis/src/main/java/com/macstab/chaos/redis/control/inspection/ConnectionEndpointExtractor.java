/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.macstab.chaos.redis.api.Endpoint;

import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Extracts the remote endpoint (host:port) from a Lettuce {@link StatefulRedisConnection}.
 *
 * <p><strong>Lettuce toString() format (as of Lettuce 6.x):</strong>
 *
 * <pre>
 * StatefulRedisConnectionImpl[remoteAddress=localhost/127.0.0.1:6379, ...]
 * </pre>
 *
 * or, depending on Netty channel state:
 *
 * <pre>
 * ...redis://127.0.0.1:6379...
 * </pre>
 *
 * This class tries three patterns in order of specificity:
 *
 * <ol>
 *   <li>{@code redis://host:port} — canonical Redis URI in toString
 *   <li>{@code remoteAddress=host:port} — Netty channel remote address field
 *   <li>{@code host:port} — generic 4–5 digit port pattern
 * </ol>
 *
 * <p><strong>Null handling:</strong> Passing {@code null} throws {@link NullPointerException}
 * immediately (fail-fast contract). Malformed strings return {@link Optional#empty()}.
 *
 * <p><strong>Thread Safety:</strong> This class is stateless — all methods are static. Regex
 * patterns are compiled once at class load time.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class ConnectionEndpointExtractor {

  /** Matches {@code redis://host:port} (most specific pattern). */
  private static final Pattern PATTERN_REDIS_URI = Pattern.compile("redis://([^:]+):(\\d+)");

  /** Matches {@code remoteAddress=host:port} (Netty channel field). */
  private static final Pattern PATTERN_REMOTE_ADDRESS =
      Pattern.compile("remoteAddress=([^:,\\s]+):(\\d+)");

  /** Matches generic {@code host:port} with 4–5 digit port (most lenient). */
  private static final Pattern PATTERN_HOST_PORT = Pattern.compile("([a-zA-Z0-9.-]+):(\\d{4,5})");

  private ConnectionEndpointExtractor() {
    throw new UnsupportedOperationException("Static utility class");
  }

  /**
   * Extracts the remote endpoint from a Lettuce connection.
   *
   * <p>Tries three parsing strategies in order. Returns {@link Optional#empty()} only if all
   * strategies fail (e.g. Lettuce changed its toString() format).
   *
   * @param connection the Lettuce connection to inspect (must not be null)
   * @return optional endpoint (host + port), empty if parsing fails
   * @throws NullPointerException if connection is null
   */
  public static Optional<Endpoint> extractEndpoint(final StatefulRedisConnection<?, ?> connection) {
    Objects.requireNonNull(connection, "connection");

    // Strategy 1: Try Netty channel reflection for most accurate result
    final Optional<Endpoint> fromChannel = extractViaNettyChannel(connection);
    if (fromChannel.isPresent()) {
      return fromChannel;
    }

    // Strategy 2: Parse connection.toString() with multiple patterns
    final String str = connection.toString();
    return extractFromString(str);
  }

  /**
   * Extracts endpoint from a connection string (without live connection).
   *
   * <p>Useful for testing and offline parsing.
   *
   * @param connectionString the toString() output of a Lettuce connection (must not be null)
   * @return optional endpoint, empty if no pattern matches
   * @throws NullPointerException if connectionString is null
   */
  public static Optional<Endpoint> extractFromString(final String connectionString) {
    Objects.requireNonNull(connectionString, "connectionString");

    final Endpoint fromUri = tryPattern(PATTERN_REDIS_URI, connectionString);
    if (fromUri != null) {
      log.trace("Extracted endpoint via REDIS_URI pattern: {}", fromUri);
      return Optional.of(fromUri);
    }

    final Endpoint fromRemoteAddr = tryPattern(PATTERN_REMOTE_ADDRESS, connectionString);
    if (fromRemoteAddr != null) {
      log.trace("Extracted endpoint via REMOTE_ADDRESS pattern: {}", fromRemoteAddr);
      return Optional.of(fromRemoteAddr);
    }

    final Endpoint fromHostPort = tryPattern(PATTERN_HOST_PORT, connectionString);
    if (fromHostPort != null) {
      log.trace("Extracted endpoint via HOST_PORT pattern: {}", fromHostPort);
      return Optional.of(fromHostPort);
    }

    log.debug("All patterns failed for connection string: {}", connectionString);
    return Optional.empty();
  }

  /**
   * Tries to extract the endpoint via Netty channel reflection.
   *
   * <p>This is the most accurate strategy as it reads the live channel state directly. Falls back
   * gracefully if Lettuce internals are unavailable.
   *
   * <p><strong>Compatibility note:</strong> Tested against Lettuce 6.5.x. Lettuce 7.x may break
   * this; toString fallbacks below are the safe path.
   *
   * @param connection the connection to inspect
   * @return optional endpoint from channel, empty if reflection fails
   */
  @SuppressWarnings({"java:S3011", "unchecked"})
  private static Optional<Endpoint> extractViaNettyChannel(
      final StatefulRedisConnection<?, ?> connection) {
    try {
      if (!(connection instanceof io.lettuce.core.StatefulRedisConnectionImpl)) {
        return Optional.empty();
      }
      final var impl = (io.lettuce.core.StatefulRedisConnectionImpl<?, ?>) connection;
      final var writer = impl.getChannelWriter();

      Object actualWriter = writer;
      try {
        final var delegateField = writer.getClass().getDeclaredField("delegate");
        delegateField.setAccessible(true);
        actualWriter = delegateField.get(writer);
      } catch (final NoSuchFieldException ignored) {
        // Not a delegating writer
      }

      Class<?> clazz = actualWriter.getClass();
      java.lang.reflect.Field channelField = null;
      while (clazz != null && channelField == null) {
        try {
          channelField = clazz.getDeclaredField("channel");
        } catch (final NoSuchFieldException e) {
          clazz = clazz.getSuperclass();
        }
      }

      if (channelField == null) {
        return Optional.empty();
      }

      channelField.setAccessible(true);
      final var channel = (io.netty.channel.Channel) channelField.get(actualWriter);
      if (channel == null || channel.remoteAddress() == null) {
        return Optional.empty();
      }

      final var remoteAddr = (java.net.InetSocketAddress) channel.remoteAddress();
      return Optional.of(new Endpoint(remoteAddr.getHostString(), remoteAddr.getPort()));

    } catch (final Exception e) {
      log.trace("Netty channel extraction failed (expected in some environments)", e);
      return Optional.empty();
    }
  }

  /** Tries a pattern against the string; returns Endpoint on match, null otherwise. */
  private static Endpoint tryPattern(final Pattern pattern, final String str) {
    final Matcher matcher = pattern.matcher(str);
    if (!matcher.find()) {
      return null;
    }
    try {
      final String host = matcher.group(1);
      final int port = Integer.parseInt(matcher.group(2));
      return new Endpoint(host, port);
    } catch (final Exception e) {
      log.trace("Pattern matched but extraction failed: {}", e.getMessage());
      return null;
    }
  }
}
