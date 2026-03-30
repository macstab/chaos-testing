/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.command;

import java.util.Objects;

/**
 * Builds redis-cli shell command strings for execution via {@link
 * com.macstab.chaos.core.util.Shell#exec}.
 *
 * <p>All methods return strings suitable for {@code Shell.exec(container, command)}. Commands
 * target specific ports and are OS-agnostic (redis-cli is always available in Redis images).
 *
 * <p><strong>INTERNAL USE ONLY</strong>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisCommandBuilder {

  /**
   * Standard Redis server port inside the container (6379).
   *
   * <p>Used as the container-internal port for all Redis node containers. The host-side mapped
   * port is always random (or explicitly set via {@code @RedisStandalone(port = N)}).
   *
   * <p><strong>Known limitation:</strong> This module assumes Redis listens on this port inside
   * every container. If you start Redis on a non-standard internal port via
   * {@code @RedisStandalone(args = {"--port", "6380"})}, port resolution will silently fail because
   * {@code getMappedPort(DEFAULT_REDIS_PORT)} looks for the wrong container port.
   * Non-standard internal ports are not currently supported end-to-end.
   */
  public static final int DEFAULT_REDIS_PORT = 6379;

  /**
   * Standard Redis Sentinel port inside the container (26379).
   *
   * <p>Same constraint applies: Sentinel containers are expected to listen on this port.
   * Non-standard Sentinel ports via custom config are not supported end-to-end.
   */
  public static final int DEFAULT_SENTINEL_PORT = 26379;

  private RedisCommandBuilder() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Build ROLE command to detect master/replica/sentinel role. */
  public static String buildRoleCommand(final int port) {
    return String.format("redis-cli -p %d ROLE", port);
  }

  /** Build PING command to check if Redis is alive. */
  public static String buildPingCommand(final int port) {
    return String.format("redis-cli -p %d PING", port);
  }

  /** Build CONFIG SET command. */
  public static String buildConfigSet(final int port, final String key, final String value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    return String.format("redis-cli -p %d CONFIG SET %s %s", port, key, value);
  }

  /**
   * Build SENTINEL master command with custom Sentinel port.
   *
   * @param masterName Sentinel master name (e.g., "mymaster") — must not be null
   * @param port Sentinel port (typically 26379)
   * @return redis-cli command string
   */
  public static String buildSentinelMasterCommand(final String masterName, final int port) {
    Objects.requireNonNull(masterName, "masterName");
    return String.format("redis-cli -p %d SENTINEL master %s", port, masterName);
  }

  /**
   * Build SENTINEL master command (default port 26379).
   *
   * @param masterName Sentinel master name — must not be null
   * @return redis-cli command string
   * @deprecated Use {@link #buildSentinelMasterCommand(String, int)} to explicitly specify port
   */
  @Deprecated(since = "2.0", forRemoval = true)
  public static String buildSentinelMasterCommand(final String masterName) {
    return buildSentinelMasterCommand(masterName, DEFAULT_SENTINEL_PORT);
  }

  /**
   * Build Sentinel startup command (inline config + launch) using default ports.
   *
   * <p>Sentinel listens on {@value #DEFAULT_SENTINEL_PORT}, monitors master on
   * {@value #DEFAULT_REDIS_PORT}. Use {@link #buildSentinelStartCommand(String, int, int, int)}
   * to configure non-standard ports.
   *
   * @param masterIp master Redis container IP — must not be null
   * @param quorum   number of Sentinels required to agree on failover
   * @return shell command string
   */
  public static String buildSentinelStartCommand(final String masterIp, final int quorum) {
    return buildSentinelStartCommand(
        masterIp, DEFAULT_REDIS_PORT, DEFAULT_SENTINEL_PORT, quorum);
  }

  /**
   * Build Sentinel startup command (inline config + launch) with explicit ports.
   *
   * @param masterIp       master Redis container IP — must not be null
   * @param masterPort     Redis master port inside the container (typically 6379)
   * @param sentinelPort   port this Sentinel will listen on (typically 26379)
   * @param quorum         number of Sentinels required to agree on failover
   * @return shell command string
   */
  public static String buildSentinelStartCommand(
      final String masterIp,
      final int masterPort,
      final int sentinelPort,
      final int quorum) {
    Objects.requireNonNull(masterIp, "masterIp");
    return "printf \"port " + sentinelPort + "\\n"
        + "sentinel monitor mymaster "
        + masterIp + " " + masterPort + " " + quorum + "\\n"
        + "sentinel down-after-milliseconds mymaster 2000\\n"
        + "sentinel parallel-syncs mymaster 1\\n"
        + "sentinel failover-timeout mymaster 5000\\n"
        + "\" > /tmp/sentinel.conf && "
        + "redis-server /tmp/sentinel.conf --sentinel";
  }

  // ==================== Inspection Commands ====================

  /**
   * Build SLOWLOG RESET command.
   *
   * @param port Redis port
   * @return redis-cli command string
   */
  public static String buildSlowlogResetCommand(final int port) {
    return String.format("redis-cli -p %d SLOWLOG RESET", port);
  }

  /**
   * Build SLOWLOG GET command.
   *
   * @param port Redis port
   * @param count max number of entries to retrieve (use 128 for typical test scenarios)
   * @return redis-cli command string
   */
  public static String buildSlowlogGetCommand(final int port, final int count) {
    return String.format("redis-cli -p %d SLOWLOG GET %d", port, count);
  }

  /**
   * Build CLIENT LIST command.
   *
   * @param port Redis port
   * @return redis-cli command string
   */
  public static String buildClientListCommand(final int port) {
    return String.format("redis-cli -p %d CLIENT LIST", port);
  }

  /**
   * Build INFO memory command.
   *
   * @param port Redis port
   * @return redis-cli command string
   */
  public static String buildInfoMemoryCommand(final int port) {
    return String.format("redis-cli -p %d INFO memory", port);
  }

  /**
   * Build generic INFO section command.
   *
   * @param port Redis port
   * @param section INFO section name (e.g., "memory", "replication", "server")
   * @return redis-cli command string
   */
  public static String buildInfoCommand(final int port, final String section) {
    Objects.requireNonNull(section, "section");
    return String.format("redis-cli -p %d INFO %s", port, section);
  }

  /**
   * Build SET command for a key/value pair.
   *
   * @param port  Redis port
   * @param key   key — must not be null
   * @param value value — must not be null
   * @return redis-cli command string
   */
  public static String buildSetCommand(final int port, final String key, final String value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    return String.format("redis-cli -p %d SET %s %s", port, key, value);
  }

  /**
   * Build GET command for a key.
   *
   * @param port Redis port
   * @param key  key — must not be null
   * @return redis-cli command string
   */
  public static String buildGetCommand(final int port, final String key) {
    Objects.requireNonNull(key, "key");
    return String.format("redis-cli -p %d GET %s", port, key);
  }

  /**
   * Build DEL command for a key.
   *
   * @param port Redis port
   * @param key  key — must not be null
   * @return redis-cli command string
   */
  public static String buildDelCommand(final int port, final String key) {
    Objects.requireNonNull(key, "key");
    return String.format("redis-cli -p %d DEL %s", port, key);
  }

  // ==================== Announce / Replication Commands ====================

  /** Build replica-announce-ip CONFIG SET (for Testcontainers host routing). */
  public static String buildAnnounceIpCommand(final int port, final String announceHost) {
    Objects.requireNonNull(announceHost, "announceHost");
    return buildConfigSet(port, "replica-announce-ip", announceHost);
  }

  /** Build replica-announce-port CONFIG SET. */
  public static String buildAnnouncePortCommand(final int port, final int announcePort) {
    return buildConfigSet(port, "replica-announce-port", String.valueOf(announcePort));
  }

  /**
   * Build sentinel-announce-ip CONFIG SET with custom Sentinel port.
   *
   * @param port Sentinel port (typically 26379)
   * @param announceHost host to announce — must not be null
   * @return redis-cli command string
   */
  public static String buildSentinelAnnounceIpCommand(final int port, final String announceHost) {
    Objects.requireNonNull(announceHost, "announceHost");
    return String.format("redis-cli -p %d CONFIG SET sentinel-announce-ip %s", port, announceHost);
  }

  /**
   * Build sentinel-announce-ip CONFIG SET (default port 26379).
   *
   * @param announceHost host to announce — must not be null
   * @return redis-cli command string
   * @deprecated Use {@link #buildSentinelAnnounceIpCommand(int, String)} to explicitly specify port
   */
  @Deprecated(since = "2.0", forRemoval = true)
  public static String buildSentinelAnnounceIpCommand(final String announceHost) {
    return buildSentinelAnnounceIpCommand(DEFAULT_SENTINEL_PORT, announceHost);
  }

  /**
   * Build sentinel-announce-port CONFIG SET with custom Sentinel port.
   *
   * @param port Sentinel port (typically 26379)
   * @param announcePort port to announce
   * @return redis-cli command string
   */
  public static String buildSentinelAnnouncePortCommand(final int port, final int announcePort) {
    return String.format("redis-cli -p %d CONFIG SET sentinel-announce-port %d", port, announcePort);
  }

  /**
   * Build sentinel-announce-port CONFIG SET (default port 26379).
   *
   * @param announcePort port to announce
   * @return redis-cli command string
   * @deprecated Use {@link #buildSentinelAnnouncePortCommand(int, int)} to explicitly specify port
   */
  @Deprecated(since = "2.0", forRemoval = true)
  public static String buildSentinelAnnouncePortCommand(final int announcePort) {
    return buildSentinelAnnouncePortCommand(DEFAULT_SENTINEL_PORT, announcePort);
  }
}
