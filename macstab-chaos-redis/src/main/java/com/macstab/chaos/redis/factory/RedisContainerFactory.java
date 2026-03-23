/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.util.ContainerIdFormatter;
import com.macstab.chaos.redis.exception.ClusterCreationException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class RedisContainerFactory {
  private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");
  private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(30);

  private RedisContainerFactory() {
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
   * @return started Redis container
   */
  public static GenericContainer<?> createStandalone() {
    return new GenericContainer<>(REDIS_IMAGE)
        .withExposedPorts(6379)
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT)
        .withReuse(false); // Fresh instance per test
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
   * @return started Redis container with TLS on port 6380
   */
  public static GenericContainer<?> createStandaloneWithSSL() {
    final Path certsDir = Paths.get("src/test/resources/certs");

    return new GenericContainer<>(REDIS_IMAGE)
        .withExposedPorts(6380) // TLS port
        .withCopyFileToContainer(
            MountableFile.forHostPath(certsDir.resolve("ca.crt")), "/tls/ca.crt")
        .withCopyFileToContainer(
            MountableFile.forHostPath(certsDir.resolve("server.crt")), "/tls/server.crt")
        .withCopyFileToContainer(
            MountableFile.forHostPath(certsDir.resolve("server.key"), 0644), "/tls/server.key")
        .withCommand(
            "redis-server",
            "--port",
            "0", // Disable non-TLS port
            "--tls-port",
            "6380",
            "--tls-cert-file",
            "/tls/server.crt",
            "--tls-key-file",
            "/tls/server.key",
            "--tls-ca-cert-file",
            "/tls/ca.crt",
            "--tls-auth-clients",
            "yes" // Require client certificates
            )
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT)
        .withReuse(false);
  }

  /**
   * Creates a Redis Sentinel cluster for high-availability testing.
   *
   * <p><strong>Topology:</strong>
   *
   * <pre>
   * Network: redis-sentinel-net
   *   ├─ redis-master   (port 6379, accepts writes)
   *   ├─ redis-replica1 (port 6379, replicates master, read-only)
   *   ├─ redis-replica2 (port 6379, replicates master, read-only)
   *   ├─ sentinel1      (port 26379, monitors master)
   *   ├─ sentinel2      (port 26379, monitors master)
   *   └─ sentinel3      (port 26379, monitors master)
   * </pre>
   *
   * <p><strong>Sentinel configuration:</strong>
   *
   * <ul>
   *   <li>Quorum: 2 (majority of 3 sentinels)
   *   <li>Down-after-milliseconds: 5000 (5 seconds)
   *   <li>Failover-timeout: 10000 (10 seconds)
   *   <li>Parallel-syncs: 1 (safe for testing)
   * </ul>
   *
   * <p><strong>Startup time:</strong> ~20-30 seconds (6 containers).
   *
   * <p><strong>Network requirements:</strong> Requires Docker host networking (Linux host or dev
   * container). Auto-disabled on macOS/Windows hosts via {@code @DisabledOnNonLinuxHost}.
   *
   * @return Sentinel cluster with all containers started
   * @throws RuntimeException if container configuration fails
   */
  public static SentinelCluster createSentinelCluster() {
    return createSentinelCluster(2, 3, false);
  }

  /**
   * Creates a Redis Sentinel cluster with configurable replica and sentinel counts.
   *
   * <p><strong>Network requirements:</strong> Requires Docker host networking (Linux host or dev
   * container). Auto-disabled on macOS/Windows hosts via {@code @DisabledOnNonLinuxHost}.
   *
   * @param replicaCount number of replicas to create (must be >= 1)
   * @param sentinelCount number of sentinels to create (must be >= 1)
   * @return Sentinel cluster with all containers started
   * @throws RuntimeException if container configuration fails
   * @throws IllegalArgumentException if replica or sentinel count is less than 1
   */
  public static SentinelCluster createSentinelCluster(
      final int replicaCount, final int sentinelCount) {
    return createSentinelCluster(replicaCount, sentinelCount, false);
  }

  /**
   * Creates a Redis Sentinel cluster with configurable replica, sentinel counts, and network chaos.
   *
   * <p><strong>Network requirements:</strong> Requires Docker host networking (Linux host or dev
   * container). Auto-disabled on macOS/Windows hosts via {@code @DisabledOnNonLinuxHost}.
   *
   * <p><strong>Network Chaos:</strong> If enabled, adds NET_ADMIN capability to all containers
   * (container-scoped only, does not affect host or other containers).
   *
   * @param replicaCount number of replicas to create (must be >= 1)
   * @param sentinelCount number of sentinels to create (must be >= 1)
   * @param enableNetworkChaos if true, adds NET_ADMIN capability for network chaos engineering
   * @return Sentinel cluster with all containers started
   * @throws RuntimeException if container configuration fails
   * @throws IllegalArgumentException if replica or sentinel count is less than 1
   * @since 2.0
   */
  public static SentinelCluster createSentinelCluster(
      final int replicaCount, final int sentinelCount, final boolean enableNetworkChaos) {
    if (replicaCount < 1) {
      throw new IllegalArgumentException("Replica count must be at least 1, got: " + replicaCount);
    }
    if (sentinelCount < 1) {
      throw new IllegalArgumentException(
          "Sentinel count must be at least 1, got: " + sentinelCount);
    }

    final int quorum = calculateQuorum(sentinelCount);
    log.info(
        "🚀 Creating Sentinel cluster: {} replicas, {} sentinels, quorum={}, networkChaos={}",
        replicaCount,
        sentinelCount,
        quorum,
        enableNetworkChaos);

    final long startTime = System.currentTimeMillis();

    // 1. Create network
    final Network network = Network.newNetwork();
    log.debug("✓ Network created: {}", network.getId());

    // 2. Start master
    log.debug("Starting master node...");
    final GenericContainer<?> master = createMasterNode(network, enableNetworkChaos);
    try {
      master.start();
      log.info(
          "✓ Master started: {} ({}ms)",
          ContainerIdFormatter.truncate(master.getContainerId()),
          System.currentTimeMillis() - startTime);
    } catch (Exception e) {
      log.error("✗ Master startup failed", e);
      throw new ClusterCreationException("Failed to start master", 1, 1, e);
    }

    // 3. Start replicas
    log.debug("Starting {} replica(s)...", replicaCount);
    final List<GenericContainer<?>> replicas = new java.util.ArrayList<>();
    for (int i = 1; i <= replicaCount; i++) {
      final long replicaStartTime = System.currentTimeMillis();
      final GenericContainer<?> replica =
          createReplicaNode(network, "redis-replica" + i, enableNetworkChaos);
      try {
        replica.start();
        final long replicaDuration = System.currentTimeMillis() - replicaStartTime;
        log.info(
            "✓ Replica {}/{} started: {} ({}ms)",
            i,
            replicaCount,
            ContainerIdFormatter.truncate(replica.getContainerId()),
            replicaDuration);
        replicas.add(replica);
      } catch (Exception e) {
        log.error("✗ Replica {}/{} startup failed", i, replicaCount, e);
        throw new ClusterCreationException("Failed to start replica", i, replicaCount, e);
      }
    }

    // 4. Get master IP for Sentinel configuration
    final String masterIp = getMasterIpAddress(master);
    log.debug("Master IP: {}", masterIp);

    // 5. Start sentinels
    log.debug("Starting {} sentinel(s) with quorum={}...", sentinelCount, quorum);
    final List<GenericContainer<?>> sentinels = new java.util.ArrayList<>();
    for (int i = 1; i <= sentinelCount; i++) {
      final long sentinelStartTime = System.currentTimeMillis();
      final GenericContainer<?> sentinel =
          createSentinelNode(network, "sentinel" + i, masterIp, quorum, enableNetworkChaos);
      try {
        sentinel.start();
        final long sentinelDuration = System.currentTimeMillis() - sentinelStartTime;
        log.info(
            "✓ Sentinel {}/{} started: {} ({}ms)",
            i,
            sentinelCount,
            ContainerIdFormatter.truncate(sentinel.getContainerId()),
            sentinelDuration);
        sentinels.add(sentinel);
      } catch (Exception e) {
        log.error("✗ Sentinel {}/{} startup failed", i, sentinelCount, e);
        throw new ClusterCreationException("Failed to start sentinel", i, sentinelCount, e);
      }
    }

    // 6. Wait for Sentinel stabilization (internal state synchronization)
    log.debug("Waiting for Sentinel stabilization...");
    waitForSentinelStabilization();
    log.debug("✓ Sentinel cluster stabilized");

    final long totalDuration = System.currentTimeMillis() - startTime;
    log.info(
        "✓ Sentinel cluster created successfully in {}ms: 1 master + {} replicas + {} sentinels",
        totalDuration,
        replicaCount,
        sentinelCount);

    return new SentinelCluster(network, master, replicas, sentinels);
  }

  /**
   * Creates Redis master node container.
   *
   * @param network Docker network
   * @param enableNetworkChaos if true, adds NET_ADMIN capability for network chaos engineering
   * @return configured master container (not started)
   */
  private static GenericContainer<?> createMasterNode(
      final Network network, final boolean enableNetworkChaos) {
    return new GenericContainer<>(REDIS_IMAGE)
        .withNetwork(network)
        .withNetworkAliases("redis-master")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--protected-mode", "no")
        .withCreateContainerCmdModifier(
            cmd -> {
              var hostConfig =
                  cmd.getHostConfig().withExtraHosts("host.testcontainers.internal:host-gateway");

              // Add NET_ADMIN capability for network chaos (container-scoped only)
              if (enableNetworkChaos) {
                hostConfig = hostConfig.withCapAdd(Capability.NET_ADMIN);
              }

              cmd.withHostConfig(hostConfig);
            })
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT);
  }

  /**
   * Creates Redis replica node container.
   *
   * @param network Docker network
   * @param alias container network alias
   * @param enableNetworkChaos if true, adds NET_ADMIN capability for network chaos engineering
   * @return configured replica container (not started)
   */
  private static GenericContainer<?> createReplicaNode(
      final Network network, final String alias, final boolean enableNetworkChaos) {
    return new GenericContainer<>(REDIS_IMAGE)
        .withNetwork(network)
        .withNetworkAliases(alias)
        .withExposedPorts(6379)
        .withCommand(
            "redis-server", "--protected-mode", "no", "--replicaof", "redis-master", "6379")
        .withCreateContainerCmdModifier(
            cmd -> {
              var hostConfig =
                  cmd.getHostConfig().withExtraHosts("host.testcontainers.internal:host-gateway");

              // Add NET_ADMIN capability for network chaos (container-scoped only)
              if (enableNetworkChaos) {
                hostConfig = hostConfig.withCapAdd(Capability.NET_ADMIN);
              }

              cmd.withHostConfig(hostConfig);
            })
        .waitingFor(Wait.forLogMessage(".*MASTER <-> REPLICA sync: Finished with success.*\\n", 1))
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT);
  }

  /**
   * Creates Sentinel node container.
   *
   * @param network Docker network
   * @param alias container network alias
   * @param masterIp master container IP address
   * @param quorum sentinel quorum
   * @param enableNetworkChaos if true, adds NET_ADMIN capability for network chaos engineering
   * @return configured sentinel container (not started)
   */
  private static GenericContainer<?> createSentinelNode(
      final Network network,
      final String alias,
      final String masterIp,
      final int quorum,
      final boolean enableNetworkChaos) {
    final String sentinelCommand = buildSentinelCommandWithoutAnnounce(masterIp, quorum);
    return new GenericContainer<>(REDIS_IMAGE)
        .withNetwork(network)
        .withNetworkAliases(alias)
        .withExposedPorts(26379)
        .withCreateContainerCmdModifier(
            cmd -> {
              var hostConfig =
                  cmd.getHostConfig().withExtraHosts("host.testcontainers.internal:host-gateway");

              // Add NET_ADMIN capability for network chaos (container-scoped only)
              if (enableNetworkChaos) {
                hostConfig = hostConfig.withCapAdd(Capability.NET_ADMIN);
              }

              cmd.withHostConfig(hostConfig);
            })
        .withCommand("sh", "-c", sentinelCommand)
        .waitingFor(
            Wait.forSuccessfulCommand("redis-cli -p 26379 SENTINEL master mymaster")
                .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT))
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT);
  }

  /**
   * Configures master to announce its externally-accessible address.
   *
   * <p>This allows Sentinel to return the correct (mapped) address to clients outside the Docker
   * network.
   *
   * @param master master container
   * @throws RuntimeException if configuration fails
   */
  private static void configureMasterAnnouncement(final GenericContainer<?> master) {
    final Integer masterMappedPort = master.getMappedPort(6379);
    try {
      master.execInContainer(
          "redis-cli", "CONFIG", "SET", "replica-announce-ip", "host.testcontainers.internal");
      master.execInContainer(
          "redis-cli", "CONFIG", "SET", "replica-announce-port", String.valueOf(masterMappedPort));
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to configure master announce address", e);
    }
  }

  /**
   * Configures replica to announce its externally-accessible address.
   *
   * <p>This allows the replica to be promoted to master during failover and properly announce
   * itself to Sentinel and clients.
   *
   * @param replica replica container
   * @throws RuntimeException if configuration fails
   */
  private static void configureReplicaAnnouncement(final GenericContainer<?> replica) {
    final Integer replicaMappedPort = replica.getMappedPort(6379);
    try {
      replica.execInContainer(
          "redis-cli", "CONFIG", "SET", "replica-announce-ip", "host.testcontainers.internal");
      replica.execInContainer(
          "redis-cli", "CONFIG", "SET", "replica-announce-port", String.valueOf(replicaMappedPort));
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to configure replica announce address", e);
    }
  }

  /**
   * Extracts master container IP address from Docker network.
   *
   * @param master master container
   * @return IP address (e.g., "172.18.0.2")
   * @throws RuntimeException if IP cannot be determined
   */
  private static String getMasterIpAddress(final GenericContainer<?> master) {
    return master.getContainerInfo().getNetworkSettings().getNetworks().values().stream()
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Master container has no network"))
        .getIpAddress();
  }

  /**
   * Builds Sentinel startup command without announce settings.
   *
   * <p>Creates inline Sentinel configuration and starts Sentinel process.
   *
   * @param masterIp master container IP (internal Docker network address)
   * @param quorum sentinel quorum (number of sentinels needed to agree on failover)
   * @return shell command string
   */
  private static String buildSentinelCommandWithoutAnnounce(
      final String masterIp, final int quorum) {
    return "printf \"port 26379\\n"
        + "sentinel monitor mymaster "
        + masterIp
        + " 6379 "
        + quorum
        + "\\n"
        + "sentinel down-after-milliseconds mymaster 2000\\n"
        + "sentinel parallel-syncs mymaster 1\\n"
        + "sentinel failover-timeout mymaster 5000\\n"
        + "\" > /tmp/sentinel.conf && "
        + "redis-server /tmp/sentinel.conf --sentinel";
  }

  /**
   * Configures Sentinel announce settings after startup.
   *
   * <p>Configures both:
   *
   * <ul>
   *   <li>Sentinel's own announce address (for other Sentinels/clients to reach it)
   *   <li>Master's announce address (for Sentinel to report to clients)
   * </ul>
   *
   * @param sentinel sentinel container
   * @throws RuntimeException if configuration fails
   */
  private static void configureSentinelAnnouncement(final GenericContainer<?> sentinel) {
    final Integer sentinelMappedPort = sentinel.getMappedPort(26379);
    try {
      // Configure Sentinel's own announce address
      sentinel.execInContainer(
          "redis-cli",
          "-p",
          "26379",
          "CONFIG",
          "SET",
          "sentinel-announce-ip",
          "host.testcontainers.internal");
      sentinel.execInContainer(
          "redis-cli",
          "-p",
          "26379",
          "CONFIG",
          "SET",
          "sentinel-announce-port",
          String.valueOf(sentinelMappedPort));
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to configure sentinel announce address", e);
    }
  }

  /**
   * Calculates appropriate quorum for given sentinel count.
   *
   * <p>Formula: majority = (sentinels / 2) + 1
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>1 sentinel → quorum 1 (100%)
   *   <li>2 sentinels → quorum 2 (100%)
   *   <li>3 sentinels → quorum 2 (majority)
   *   <li>5 sentinels → quorum 3 (majority)
   * </ul>
   *
   * @param sentinelCount number of sentinels
   * @return quorum value
   */
  private static int calculateQuorum(final int sentinelCount) {
    return (sentinelCount / 2) + 1;
  }

  /**
   * Waits for Sentinel cluster to stabilize.
   *
   * <p>Even after +monitor event, Sentinels need time (~2s) to synchronize internal state and
   * become fully queryable.
   */
  private static void waitForSentinelStabilization() {
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for Sentinel stabilization", e);
    }
  }

  /**
   * Sentinel cluster holder (network + all containers).
   *
   * <p><strong>Lifecycle:</strong> Caller must call {@code stop()} when done, or implement {@link
   * AutoCloseable} and use try-with-resources.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * SentinelCluster cluster = RedisContainerFactory.createSentinelCluster();
   * try {
   *   // Use cluster
   *   GenericContainer<?> sentinel = cluster.firstSentinel();
   *   String host = sentinel.getHost();
   *   Integer port = sentinel.getMappedPort(26379);
   * } finally {
   *   cluster.stop();
   * }
   * }</pre>
   *
   * @param network Docker network (shared by all containers)
   * @param master Redis master container
   * @param replicas Redis replica containers (typically 2)
   * @param sentinels Sentinel containers (typically 3)
   */
  public record SentinelCluster(
      Network network,
      GenericContainer<?> master,
      List<GenericContainer<?>> replicas,
      List<GenericContainer<?>> sentinels) {

    public SentinelCluster {
      Objects.requireNonNull(network, "network");
      Objects.requireNonNull(master, "master");
      Objects.requireNonNull(replicas, "replicas");
      Objects.requireNonNull(sentinels, "sentinels");
    }

    /**
     * Stops all containers and closes the Docker network.
     *
     * <p>Call in {@code @AfterEach} or {@code @AfterAll}.
     */
    public void stop() {
      sentinels.forEach(GenericContainer::stop);
      replicas.forEach(GenericContainer::stop);
      master.stop();
      network.close();
    }

    /**
     * Returns first Sentinel container (for client connection).
     *
     * <p>Use this to get Sentinel connection details:
     *
     * <pre>{@code
     * GenericContainer<?> sentinel = cluster.firstSentinel();
     * String host = sentinel.getHost();
     * Integer port = sentinel.getMappedPort(26379);
     * }</pre>
     *
     * @return first sentinel container
     */
    public GenericContainer<?> firstSentinel() {
      return sentinels.get(0);
    }
  }
}
