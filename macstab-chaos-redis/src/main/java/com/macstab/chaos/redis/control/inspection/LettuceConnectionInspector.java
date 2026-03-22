/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.control.role.ContainerRole;
import com.macstab.chaos.redis.control.role.RoleResolver;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Lettuce-specific connection inspector with 3-tier inspection strategy.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>✅ Tier 1: Auto-detection via robust toString() parsing (multiple patterns)
 *   <li>✅ Tier 2: Explicit container hint (100% reliable)
 *   <li>✅ Tier 3: Manual inspection (full control)
 *   <li>✅ Resolves container role via {@link RoleResolver}
 *   <li>✅ Checks connection health with PING command
 *   <li>✅ Thread-safe (immutable after construction)
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Immutable after construction. Multiple threads can inspect
 * connections concurrently.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Tier 1: Auto-detection (easiest)
 * ConnectionInfo info = inspector.inspect(connection);
 *
 * // Tier 2: Explicit hint (if auto-detection fails)
 * GenericContainer<?> replica = cluster.getReplicaContainers().get(0);
 * ConnectionInfo info = inspector.inspect(connection, replica);
 *
 * // Tier 3: Manual (full control)
 * ConnectionInfo info = inspector.inspectManual(container, "Master node");
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class LettuceConnectionInspector implements ConnectionInspector {

  private static final Logger LOGGER = LoggerFactory.getLogger(LettuceConnectionInspector.class);

  // Regex patterns for robust endpoint extraction (tried in order)
  private static final Pattern PATTERN_REDIS_URI = Pattern.compile("redis://([^:]+):(\\d+)");
  private static final Pattern PATTERN_REMOTE_ADDRESS =
      Pattern.compile("remoteAddress=([^:,\\s]+):(\\d+)");
  private static final Pattern PATTERN_HOST_PORT = Pattern.compile("([a-zA-Z0-9.-]+):(\\d{4,5})");

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
          "Cannot inspect closed connection. "
              + "Connection was already closed before inspection. "
              + "Ensure connection.isOpen() == true before calling inspect().");
    }

    try {
      // Extract remote endpoint with robust parsing
      final RemoteEndpoint endpoint = extractRemoteEndpoint(connection);

      // Find matching container
      final GenericContainer<?> container =
          findMatchingContainer(endpoint).orElseThrow(() -> buildNoMatchException(endpoint));

      // Resolve role
      final ContainerRole role = roleResolver.resolve(container);

      // Check health
      final boolean healthy = checkHealth(connection);

      final String connectionInfo =
          String.format(
              "%s (%s) - %s",
              role, endpoint.toConnectionString(), healthy ? "HEALTHY" : "UNHEALTHY");

      LOGGER.debug("Inspected connection (auto-detect): {}", connectionInfo);

      return new ConnectionInfo(role, container, connectionInfo, healthy);

    } catch (Exception e) {
      LOGGER.error("Failed to inspect connection (auto-detect)", e);
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
      throw new IllegalStateException(
          "Cannot inspect closed connection. "
              + "Connection was already closed before inspection.");
    }

    if (!containerHint.isRunning()) {
      throw new IllegalStateException(
          "Container is not running: "
              + containerHint.getContainerId()
              + ". "
              + "Ensure container is started before inspection.");
    }

    // Resolve role
    final ContainerRole role = roleResolver.resolve(containerHint);

    // Check health
    final boolean healthy = checkHealth(connection);

    final String connectionInfo =
        String.format("%s (user-provided container) - %s", role, healthy ? "HEALTHY" : "UNHEALTHY");

    LOGGER.debug("Inspected connection (explicit hint): {}", connectionInfo);

    return new ConnectionInfo(role, containerHint, connectionInfo, healthy);
  }

  // ==================== Tier 3: Manual ====================

  @Override
  public ConnectionInfo inspectManual(
      final GenericContainer<?> container, final String connectionDescription) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(connectionDescription, "connectionDescription");

    if (!container.isRunning()) {
      throw new IllegalStateException(
          "Container is not running: "
              + container.getContainerId()
              + ". "
              + "Ensure container is started before inspection.");
    }

    // Resolve role
    final ContainerRole role = roleResolver.resolve(container);

    final String fullDescription = String.format("%s (%s) - MANUAL", role, connectionDescription);

    LOGGER.debug("Inspected manually: {}", fullDescription);

    return new ConnectionInfo(role, container, fullDescription, true);
  }

  // ==================== Robust Endpoint Extraction ====================

  /**
   * Extracts remote endpoint (host:port) from Lettuce connection using multiple strategies.
   *
   * @param connection the connection to inspect
   * @return remote endpoint
   * @throws IllegalStateException if all strategies fail
   */
  private RemoteEndpoint extractRemoteEndpoint(final StatefulRedisConnection<?, ?> connection) {
    // Strategy 1: Extract from Netty channel via ChannelWriter delegation chain
    try {
      if (connection instanceof io.lettuce.core.StatefulRedisConnectionImpl) {
        final var impl = (io.lettuce.core.StatefulRedisConnectionImpl<?, ?>) connection;
        final var writer = impl.getChannelWriter();

        // Writer might be wrapped (e.g., CommandExpiryWriter)
        // Unwrap to get the actual RedisChannelWriter with channel field
        Object actualWriter = writer;

        // Check if this is a delegating writer
        try {
          final var delegateField = writer.getClass().getDeclaredField("delegate");
          delegateField.setAccessible(true);
          actualWriter = delegateField.get(writer);
        } catch (NoSuchFieldException e) {
          // Not a delegating writer, use as-is
        }

        // Now look for channel field in the actual writer or its parent classes
        Class<?> clazz = actualWriter.getClass();
        java.lang.reflect.Field channelField = null;

        while (clazz != null && channelField == null) {
          try {
            channelField = clazz.getDeclaredField("channel");
          } catch (NoSuchFieldException e) {
            clazz = clazz.getSuperclass();
          }
        }

        if (channelField != null) {
          channelField.setAccessible(true);
          final var channel = (io.netty.channel.Channel) channelField.get(actualWriter);

          if (channel != null && channel.remoteAddress() != null) {
            final var remoteAddr = (java.net.InetSocketAddress) channel.remoteAddress();
            final String host = remoteAddr.getHostString();
            final int port = remoteAddr.getPort();
            LOGGER.debug("Extracted endpoint from Netty channel: {}:{}", host, port);
            return new RemoteEndpoint(host, port);
          }
        }
      }
    } catch (Exception e) {
      LOGGER.trace("Failed to extract endpoint via channel writer", e);
    }

    // Strategy 2: Parse connection toString() with multiple patterns
    final String connectionString = connection.toString();

    // Try pattern 1: redis://host:port (most specific)
    RemoteEndpoint endpoint = tryPattern(PATTERN_REDIS_URI, connectionString);
    if (endpoint != null) {
      LOGGER.trace("Extracted endpoint using REDIS_URI pattern: {}", endpoint);
      return endpoint;
    }

    // Try pattern 2: remoteAddress=host:port
    endpoint = tryPattern(PATTERN_REMOTE_ADDRESS, connectionString);
    if (endpoint != null) {
      LOGGER.trace("Extracted endpoint using REMOTE_ADDRESS pattern: {}", endpoint);
      return endpoint;
    }

    // Try pattern 3: generic host:port (most lenient)
    endpoint = tryPattern(PATTERN_HOST_PORT, connectionString);
    if (endpoint != null) {
      LOGGER.trace("Extracted endpoint using HOST_PORT pattern: {}", endpoint);
      return endpoint;
    }

    // All strategies failed - throw actionable error
    throw new IllegalStateException(
        "Cannot auto-detect container from connection.\n"
            + "Lettuce connection string: "
            + connectionString
            + "\n\n"
            + "Possible solutions:\n"
            + "1. Use explicit hint: inspect(connection, containerHint)\n"
            + "2. Use manual: inspectManual(container, \"description\")\n"
            + "3. Report this issue with connection string above\n\n"
            + "This typically happens when Lettuce changes its toString() format.");
  }

  /**
   * Tries to extract endpoint using a regex pattern.
   *
   * @param pattern regex pattern with groups: (host)(port)
   * @param connectionString connection toString() output
   * @return endpoint if successful, null otherwise
   */
  private RemoteEndpoint tryPattern(final Pattern pattern, final String connectionString) {
    final Matcher matcher = pattern.matcher(connectionString);
    if (matcher.find()) {
      try {
        final String host = matcher.group(1);
        final int port = Integer.parseInt(matcher.group(2));
        return new RemoteEndpoint(host, port);
      } catch (Exception e) {
        LOGGER.trace("Pattern matched but extraction failed: {}", e.getMessage());
        return null;
      }
    }
    return null;
  }

  // ==================== Container Matching ====================

  /**
   * Finds the container matching the remote endpoint.
   *
   * @param endpoint the endpoint to match
   * @return matching container (optional)
   */
  private Optional<GenericContainer<?>> findMatchingContainer(final RemoteEndpoint endpoint) {
    return containers.stream()
        .filter(GenericContainer::isRunning)
        .filter(container -> matchesEndpoint(container, endpoint))
        .findFirst();
  }

  /**
   * Checks if a container matches the endpoint.
   *
   * @param container the container to check
   * @param endpoint the endpoint to match
   * @return true if matches
   */
  private boolean matchesEndpoint(
      final GenericContainer<?> container, final RemoteEndpoint endpoint) {
    final String containerHost = container.getHost();
    final int containerPort = container.getFirstMappedPort();

    return endpoint.host().equals(containerHost) && endpoint.port() == containerPort;
  }

  /** Builds actionable exception when no container matches endpoint. */
  private IllegalStateException buildNoMatchException(final RemoteEndpoint endpoint) {
    final StringBuilder sb = new StringBuilder();
    sb.append("No container found matching endpoint: ")
        .append(endpoint.toConnectionString())
        .append("\n\n");
    sb.append("Available containers:\n");

    containers.stream()
        .filter(GenericContainer::isRunning)
        .forEach(
            c -> {
              final String host = c.getHost();
              final int port = c.getFirstMappedPort();
              final ContainerRole role = roleResolver.resolve(c);
              sb.append("  - ")
                  .append(role)
                  .append(" @ ")
                  .append(host)
                  .append(":")
                  .append(port)
                  .append("\n");
            });

    sb.append("\nPossible causes:\n");
    sb.append("1. Container was stopped/killed (check container.isRunning())\n");
    sb.append("2. Wrong cluster instance (verify SentinelCluster ID)\n");
    sb.append("3. Connection from different cluster\n\n");
    sb.append("Workaround: Use inspect(connection, containerHint) with explicit container");

    return new IllegalStateException(sb.toString());
  }

  // ==================== Health Check ====================

  /**
   * Checks connection health via PING command.
   *
   * @param connection the connection to check
   * @return true if healthy
   */
  private boolean checkHealth(final StatefulRedisConnection<?, ?> connection) {
    try {
      final String pong = connection.sync().ping();
      return "PONG".equalsIgnoreCase(pong);
    } catch (Exception e) {
      LOGGER.warn("Health check failed: {}", e.getMessage());
      return false;
    }
  }

  // ==================== Internal Record ====================

  /**
   * Remote endpoint (host:port).
   *
   * @param host remote host
   * @param port remote port
   */
  private record RemoteEndpoint(String host, int port) {

    RemoteEndpoint {
      Objects.requireNonNull(host, "host");
      if (port <= 0 || port > 65535) {
        throw new IllegalArgumentException("Invalid port: " + port);
      }
    }

    String toConnectionString() {
      return host + ":" + port;
    }
  }
}
