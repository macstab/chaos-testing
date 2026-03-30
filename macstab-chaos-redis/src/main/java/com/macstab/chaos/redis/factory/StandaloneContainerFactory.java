/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.macstab.chaos.redis.command.RedisCommandBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * Factory for standalone (non-clustered) Redis containers.
 *
 * <p><strong>Responsibilities:</strong>
 *
 * <ul>
 *   <li>Creating plain standalone Redis containers
 *   <li>Creating TLS/SSL-enabled standalone Redis containers
 * </ul>
 *
 * <p><strong>Design:</strong> Static utility class — all methods are static, no instances allowed.
 * Use {@link SentinelContainerFactory} for Sentinel cluster containers.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * GenericContainer<?> redis = StandaloneContainerFactory.createStandalone();
 * redis.start();
 * try {
 *   // Use redis
 * } finally {
 *   redis.stop();
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 * @see SentinelContainerFactory
 */
@Slf4j
public final class StandaloneContainerFactory {

  static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");
  static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(30);

  /** Standard Redis server port inside the container. */
  static final int REDIS_PORT = RedisCommandBuilder.DEFAULT_REDIS_PORT;

  /** Standard Redis Sentinel port inside the container. */
  static final int SENTINEL_PORT = RedisCommandBuilder.DEFAULT_SENTINEL_PORT;

  /** Docker network alias for the master node — referenced by replicas and sentinels. */
  static final String MASTER_NETWORK_ALIAS = "redis-master";

  private StandaloneContainerFactory() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Creates a standalone Redis container (no authentication, no SSL).
   *
   * <p><strong>Configuration:</strong>
   *
   * <ul>
   *   <li>Image: {@code redis:7-alpine}
   *   <li>Port: 6379 (mapped to random host port)
   *   <li>Auth: none
   *   <li>SSL: disabled
   *   <li>Protected mode: enabled (standard)
   * </ul>
   *
   * <p><strong>Startup time:</strong> ~2-3 seconds.
   *
   * @return configured Redis container (not started)
   */
  public static GenericContainer<?> createStandalone() {
    return new GenericContainer<>(REDIS_IMAGE)
        .withExposedPorts(6379)
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT)
        .withReuse(false);
  }

  /**
   * Creates a standalone Redis container with TLS/SSL enabled (mutual TLS).
   *
   * <p><strong>Configuration:</strong>
   *
   * <ul>
   *   <li>Image: {@code redis:7-alpine}
   *   <li>Port: 6380 (TLS)
   *   <li>Auth: mutual TLS (client certificates required)
   *   <li>Certificates: {@code src/test/resources/certs/} (valid until 2036)
   * </ul>
   *
   * <p><strong>Certificate structure:</strong>
   *
   * <pre>
   * src/test/resources/certs/
   *   ├── ca.crt         (Certificate Authority)
   *   ├── server.crt     (Server certificate)
   *   ├── server.key     (Server private key)
   *   ├── client.crt     (Client certificate)
   *   └── client.key     (Client private key)
   * </pre>
   *
   * <p><strong>Startup time:</strong> ~2-3 seconds.
   *
   * @return configured Redis container with TLS on port 6380 (not started)
   */
  public static GenericContainer<?> createStandaloneWithSSL() {
    final Path certsDir = Paths.get("src/test/resources/certs");
    return new GenericContainer<>(REDIS_IMAGE)
        .withExposedPorts(6380)
        .withCopyFileToContainer(
            MountableFile.forHostPath(certsDir.resolve("ca.crt")), "/tls/ca.crt")
        .withCopyFileToContainer(
            MountableFile.forHostPath(certsDir.resolve("server.crt")), "/tls/server.crt")
        .withCopyFileToContainer(
            MountableFile.forHostPath(certsDir.resolve("server.key"), 0644), "/tls/server.key")
        .withCommand(
            "redis-server",
            "--port",
            "0",
            "--tls-port",
            "6380",
            "--tls-cert-file",
            "/tls/server.crt",
            "--tls-key-file",
            "/tls/server.key",
            "--tls-ca-cert-file",
            "/tls/ca.crt",
            "--tls-auth-clients",
            "yes")
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT)
        .withReuse(false);
  }
}
