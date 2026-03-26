/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.annotation.ChaosTest;
import com.macstab.chaos.core.api.ChaosContainers;
import com.macstab.chaos.core.api.ContainerManager;
import com.macstab.chaos.redis.api.SentinelRedis;
import com.macstab.chaos.network.condition.DisabledOnNonLinuxHost;
import com.macstab.chaos.redis.extension.SentinelContainerExtension.SentinelCluster;

/**
 * Starts a full Redis Sentinel cluster for integration tests using Testcontainers.
 *
 * <p><strong>Cluster Architecture:</strong>
 *
 * <ul>
 *   <li>1 Redis master
 *   <li>N Redis replicas (configurable, default: 2)
 *   <li>M Sentinel monitors (configurable, default: 3)
 *   <li>All connected via Docker network
 * </ul>
 *
 * <p><strong>Platform Requirements:</strong>
 *
 * <p>Redis Sentinel with Testcontainers requires native Docker networking (host network mode),
 * which only works on:
 *
 * <ul>
 *   <li>✅ Linux host
 *   <li>✅ Dev containers / CI containers (even on macOS/Windows host)
 *   <li>❌ macOS host (Docker Desktop uses VM)
 *   <li>❌ Windows host (Docker Desktop uses WSL2/Hyper-V)
 * </ul>
 *
 * <p><strong>Auto-Disabled:</strong> This annotation includes {@link DisabledOnNonLinuxHost}, so
 * tests are automatically skipped on macOS/Windows hosts.
 *
 * <p><strong>Basic Usage:</strong>
 *
 * <pre>{@code
 * @RedisSentinel
 * class SentinelFailoverTest {
 *
 *   @Test
 *   void testFailover(RedisSentinelInfo info) {
 *     // Sentinel cluster running
 *     // Master: info.getMasterHost():info.getMasterPort()
 *     // Sentinels: info.getSentinelNodes()
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Custom Configuration:</strong>
 *
 * <pre>{@code
 * @RedisSentinel(
 *   masterName = "ha-master",
 *   replicas = 3,
 *   sentinels = 5,
 *   quorum = 3
 * )
 * class HighAvailabilityTest {
 *   // 1 master + 3 replicas + 5 sentinels (quorum=3)
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see SentinelContainerExtension
 * @see DisabledOnNonLinuxHost
 * @see RedisSentinels
 * @since 1.0
 */
@ChaosTest
@Repeatable(RedisSentinels.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DisabledOnNonLinuxHost(
    "Redis Sentinel tests require native Docker networking (Linux host or dev container)")
public @interface RedisSentinel {

  /**
   * Programmatic access to sentinel Redis clusters.
   *
   * <p><strong>Single Instance:</strong>
   *
   * <pre>{@code
   * SentinelRedis cluster = RedisSentinel.INSTANCE.get("session");
   * JedisSentinelPool pool = new JedisSentinelPool(
   *   cluster.masterName(),
   *   toSet(cluster.sentinels())
   * );
   * }</pre>
   *
   * <p><strong>Multiple Instances:</strong>
   *
   * <pre>{@code
   * List<SentinelRedis> all = RedisSentinel.INSTANCE.getAll();
   * }</pre>
   *
   * @since 2.0
   */
  ContainerManager<SentinelRedis> INSTANCE =
      new ContainerManager<>(
          id -> ChaosContainers.get(RedisSentinel.class, id),
          () -> ChaosContainers.getAll(RedisSentinel.class));

  /**
   * Cluster ID (unique within test class).
   *
   * <p>Default: {@code "default"}
   *
   * <p>Use when you need multiple Sentinel clusters:
   *
   * <pre>{@code
   * @RedisSentinel(id = "primary", replicas = 2)
   * @RedisSentinel(id = "secondary", replicas = 1)
   * class MultiClusterTest { ... }
   * }</pre>
   *
   * @return cluster ID
   */
  String id() default "default";

  /**
   * Redis Docker image version tag.
   *
   * <p>Default: {@code "7.4"} (latest stable)
   *
   * @return Docker image tag
   */
  String version() default "7.4";

  /**
   * Sentinel master name (used in Sentinel configuration).
   *
   * <p>Default: {@code "mymaster"}
   *
   * @return master name
   */
  String masterName() default "mymaster";

  /**
   * Number of Redis replicas (slaves).
   *
   * <p>Default: {@code 2} (1 master + 2 replicas = 3 data nodes)
   *
   * <p>Minimum: {@code 1} (at least one replica for HA)
   *
   * @return replica count
   */
  int replicas() default 2;

  /**
   * Number of Sentinel monitor instances.
   *
   * <p>Default: {@code 3} (standard HA setup)
   *
   * <p>Minimum: {@code 1} (but 3 recommended for real quorum)
   *
   * @return sentinel count
   */
  int sentinels() default 3;

  /**
   * Quorum for Sentinel failover decisions.
   *
   * <p>Default: {@code 2} (majority of 3 sentinels)
   *
   * <p>Formula: {@code quorum = (sentinels / 2) + 1} (majority)
   *
   * @return quorum value
   */
  int quorum() default 2;

  /**
   * Enable network chaos engineering capabilities (latency, packet loss, partitions).
   *
   * <p>Default: {@code false} (secure by default)
   *
   * <p><strong>Security Model:</strong>
   *
   * <ul>
   *   <li>✅ Adds NET_ADMIN capability to containers (container-scoped only)
   *   <li>✅ Each container's network namespace is isolated
   *   <li>✅ Host VM and other containers are unaffected
   *   <li>❌ Does NOT use privileged mode (too dangerous)
   * </ul>
   *
   * <p><strong>What This Enables:</strong>
   *
   * <ul>
   *   <li>Per-container latency injection (e.g., R1: 10ms, R2: 100ms)
   *   <li>Per-container packet loss (e.g., R2: 5% loss, others: 0%)
   *   <li>Network partitions between specific containers
   *   <li>Jitter simulation (variable latency)
   * </ul>
   *
   * <p><strong>Example (Cross-Region Replication Testing):</strong>
   *
   * <pre>{@code
   * @RedisSentinel(replicas = 2, enableNetworkChaos = true)
   * class NetworkChaosTest {
   *
   *   @Test
   *   void testSlowReplica(SentinelCluster cluster, ControlFacade control) {
   *     // Simulate EU replica with 80ms latency
   *     GenericContainer<?> replicaEU = cluster.getReplicas().get(0);
   *     control.network().injectLatency(replicaEU, Duration.ofMillis(80));
   *
   *     // Master and other replicas unaffected
   *     // ...
   *   }
   * }
   * }</pre>
   *
   * <p><strong>Requirements:</strong>
   *
   * <ul>
   *   <li>Linux host (native or dev container)
   *   <li>iproute2 package (included in official Redis images)
   * </ul>
   *
   * @return true to enable network chaos, false otherwise
   * @since 2.0
   */
  boolean enableNetworkChaos() default false;

  /**
   * Packages to install in ALL Redis containers (master, replicas, sentinels) using the universal
   * package manager.
   *
   * <p>Default: {@code []} (no additional packages)
   *
   * <p><strong>Purpose:</strong> Annotation-driven package installation for ALL containers in the
   * Sentinel cluster, automatically detecting the distribution and using the appropriate package
   * manager (APT, APK, DNF, YUM, PACMAN, ZYPPER).
   *
   * <p><strong>Scope:</strong> Packages are installed in:
   *
   * <ul>
   *   <li>Master container
   *   <li>All replica containers
   *   <li>All sentinel containers
   * </ul>
   *
   * <p><strong>Key Features:</strong>
   *
   * <ul>
   *   <li>Universal: Works with Debian, Alpine, Fedora, Arch, openSUSE, CentOS
   *   <li>Automatic: Detects distribution and selects package manager
   *   <li>Verified: Checks installation with 'which' command
   *   <li>Deduplication: Removes duplicate packages automatically
   * </ul>
   *
   * <p><strong>Common Use Cases:</strong>
   *
   * <pre>{@code
   * // Install testing utilities in all containers
   * @RedisSentinel(
   *   replicas = 2,
   *   sentinels = 3,
   *   packages = {"curl", "jq", "vim"}
   * )
   *
   * // Install network tools for chaos testing
   * @RedisSentinel(
   *   replicas = 2,
   *   packages = {"iproute2", "iptables"},
   *   enableNetworkChaos = true
   * )
   *
   * // Multi-cluster with different packages
   * @RedisSentinel(id = "primary", replicas = 2, packages = {"redis-tools"})
   * @RedisSentinel(id = "cache", replicas = 1, packages = {"curl"})
   * }</pre>
   *
   * <p><strong>Installation Process:</strong>
   *
   * <ol>
   *   <li>All containers start (master + replicas + sentinels)
   *   <li>For each container:
   *       <ul>
   *         <li>Detect Linux distribution from /etc/os-release
   *         <li>Select appropriate package manager
   *         <li>Deduplicate package list
   *         <li>Run package manager install command
   *         <li>Verify installation with 'which' command
   *       </ul>
   * </ol>
   *
   * <p><strong>Performance:</strong>
   *
   * <ul>
   *   <li>Per container: 2-6 seconds depending on distribution
   *   <li>Total time: (master + replicas + sentinels) × install time
   *   <li>Example: 6 containers × 3 seconds = ~18 seconds
   *   <li>Docker layer caching speeds up subsequent runs
   * </ul>
   *
   * <p><strong>Error Handling:</strong>
   *
   * <ul>
   *   <li>Package not found → {@link com.macstab.chaos.core.exception.PackageInstallationException}
   *   <li>Unsupported distribution → {@link IllegalStateException}
   *   <li>Verification failed → {@link
   *       com.macstab.chaos.core.exception.PackageInstallationException}
   *   <li>Installation failure in any container → Logs warning, continues with other containers
   * </ul>
   *
   * <p><strong>Best Practices:</strong>
   *
   * <pre>{@code
   * // ✅ Good: Install minimal packages needed for testing
   * @RedisSentinel(replicas = 2, packages = {"curl", "jq"})
   *
   * // ❌ Bad: Installing too many packages slows cluster startup
   * @RedisSentinel(replicas = 2, packages = {"curl", "vim", "emacs", "htop", ...})
   *
   * // ✅ Good: Combine with network chaos for comprehensive testing
   * @RedisSentinel(
   *   replicas = 2,
   *   sentinels = 3,
   *   packages = {"iproute2", "iptables"},
   *   enableNetworkChaos = true
   * )
   * }</pre>
   *
   * @return array of package names (empty = no packages to install)
   * @since 2.0
   * @see com.macstab.chaos.core.util.PackageInstaller
   * @see com.macstab.chaos.core.util.PackageManager
   */
  String[] packages() default {};
}
