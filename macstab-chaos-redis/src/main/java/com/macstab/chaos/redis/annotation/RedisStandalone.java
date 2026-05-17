/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import com.macstab.chaos.core.annotation.ChaosTest;
import com.macstab.chaos.core.api.ContainerManager;
import com.macstab.chaos.redis.api.StandaloneRedis;
import com.macstab.chaos.redis.extension.RedisContainerExtension;

/**
 * Starts a standalone Redis container for integration tests using Testcontainers.
 *
 * <p>The container is automatically started before all tests in the class and stopped after all
 * tests complete. Connection details are made available via {@link RedisContainerExtension.Store}.
 *
 * <p><strong>Basic Usage:</strong>
 *
 * <pre>{@code
 * @RedisStandalone
 * class MyRedisTest {
 *
 *   @Test
 *   void test(RedisConnectionInfo info) {
 *     // Redis running on info.getHost() : info.getPort()
 *   }i
 * }
 * }</pre>
 *
 * <p><strong>Custom Configuration:</strong>
 *
 * <pre>{@code
 * @RedisStandalone(
 *   version = "7.4",
 *   args = {"--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"}
 * )
 * class CacheEvictionTest {
 *   // Redis 7.4 with custom memory config
 * }
 * }</pre>
 *
 * <p><strong>Container Lifecycle:</strong>
 *
 * <ul>
 *   <li>Scope: {@code @BeforeAll} / {@code @AfterAll} (class-level, singleton)
 *   <li>Reuse: Same container for all tests in class
 *   <li>Cleanup: Automatic (Testcontainers handles shutdown)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see RedisContainerExtension
 * @see RedisSentinel
 * @see RedisStandalones
 * @since 1.0
 */
@ChaosTest
@ExtendWith(RedisContainerExtension.class)
@Repeatable(RedisStandalones.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisStandalone {

  /**
   * Programmatic access to standalone Redis containers.
   *
   * <p><strong>Single Instance:</strong>
   *
   * <pre>{@code
   * StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
   * Jedis jedis = new Jedis(cache.host(), cache.port());
   * }</pre>
   *
   * <p><strong>Multiple Instances:</strong>
   *
   * <pre>{@code
   * List<StandaloneRedis> all = RedisStandalone.INSTANCE.getAll();
   * }</pre>
   *
   * @since 1.0
   */
  ContainerManager<StandaloneRedis> INSTANCE =
      new ContainerManager<>(
          id -> {
            final RedisContainerExtension.RedisConnectionInfo info =
                RedisContainerExtension.getContainer(id);
            return new StandaloneRedis(info.getHost(), info.getPort());
          },
          () ->
              RedisContainerExtension.getAllContainers().stream()
                  .map(info -> new StandaloneRedis(info.getHost(), info.getPort()))
                  .toList());

  /**
   * Container ID (unique within test class).
   *
   * <p>Default: {@code "default"}
   *
   * <p>Use when you need multiple Redis containers:
   *
   * <pre>{@code
   * @RedisStandalone(id = "master", version = "7.4")
   * @RedisStandalone(id = "cache", version = "7.2")
   * class MultiRedisTest { ... }
   * }</pre>
   *
   * @return container ID
   */
  String id() default "default";

  /**
   * Redis Docker image version tag.
   *
   * <p>Default: {@code "7.4"} (latest stable as of 2026)
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "7.4"} (latest 7.x)
   *   <li>{@code "7.2-alpine"} (Alpine Linux variant)
   *   <li>{@code "6.2"} (older version)
   * </ul>
   *
   * @return Docker image tag
   */
  String version() default "7.4";

  /**
   * Exposed host port for Redis.
   *
   * <p>Default: {@code 0} (random available port, recommended for CI)
   *
   * <p>Use fixed port only for debugging:
   *
   * <pre>{@code
   * @RedisStandalone(port = 6379) // Always localhost:6379
   * }</pre>
   *
   * @return host port (0 = random)
   */
  int port() default 0;

  /**
   * Additional Redis server command-line arguments.
   *
   * <p>Default: {@code []} (no extra args)
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @RedisStandalone(args = {"--requirepass", "secret", "--maxclients", "100"})
   * }</pre>
   *
   * <p><strong>Port argument limitation:</strong> Do NOT use {@code --port} here to change the
   * internal Redis port. The module resolves the mapped host port via {@code
   * getMappedPort(RedisCommandBuilder.DEFAULT_REDIS_PORT)} — if the container listens on a
   * different port, connection info will be wrong and MONITOR-based tools will fail to connect.
   * Internal port configuration is not currently supported end-to-end.
   *
   * @return Redis server CLI arguments (passed directly to {@code redis-server})
   */
  String[] args() default {};

  /**
   * Enable network chaos engineering capabilities (latency, packet loss).
   *
   * <p>Default: {@code false} (secure by default)
   *
   * <p><strong>Security Model:</strong>
   *
   * <ul>
   *   <li>✅ Adds NET_ADMIN capability to container (container-scoped only)
   *   <li>✅ Container's network namespace is isolated
   *   <li>✅ Host VM and other containers are unaffected
   *   <li>❌ Does NOT use privileged mode (too dangerous)
   * </ul>
   *
   * <p><strong>What This Enables:</strong>
   *
   * <ul>
   *   <li>Latency injection (simulate slow networks)
   *   <li>Packet loss simulation (simulate unreliable networks)
   *   <li>Jitter simulation (variable latency)
   * </ul>
   *
   * <p><strong>Example (Slow Network Testing):</strong>
   *
   * <pre>{@code
   * @RedisStandalone(enableNetworkChaos = true)
   * class NetworkChaosTest {
   *
   *   @Test
   *   void testSlowNetwork(RedisConnectionInfo redis, ControlFacade control) {
   *     // Simulate 100ms latency
   *     control.network().injectLatency(redis.getContainer(), Duration.ofMillis(100));
   *
   *     // Test if system handles slow network
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
   * @since 1.0
   */
  boolean enableNetworkChaos() default false;

  /**
   * Enable socket-syscall-level connection chaos via {@code libchaos-net} ({@code LD_PRELOAD}) plus
   * automatic Toxiproxy proxy-level fallback.
   *
   * <p>Default: {@code false} (secure by default)
   *
   * <p><strong>Orthogonal to {@link #enableNetworkChaos}:</strong> both flags may be {@code true}
   * simultaneously — they cover different fault layers. {@link #enableNetworkChaos} operates on the
   * kernel packet path ({@code tc/netem} + {@code iptables}); this flag operates on libc socket
   * calls intercepted by {@code libchaos-net}. Use both to test packet-level and syscall-level
   * failure modes independently.
   *
   * <p><strong>What This Enables:</strong>
   *
   * <ul>
   *   <li>Per-syscall errno injection on {@code connect}, {@code bind}, {@code accept}, {@code
   *       send}, {@code recv}, {@code poll}
   *   <li>UDP / unix-socket / DNS-level fault injection (Toxiproxy cannot reach these layers)
   *   <li>{@code recv}-corruption, file-descriptor exhaustion, listen/accept faults
   *   <li>Bandwidth shaping via the Toxiproxy fallback inside {@code CompositeConnectionChaos}
   * </ul>
   *
   * <p><strong>Lifecycle:</strong> When {@code true}, {@code LibchaosTransport(LibchaosLib.NET)
   * .prepare(container)} is invoked <em>before</em> {@code container.start()} so the dynamic loader
   * honours the {@code LD_PRELOAD} hook at process launch. The Toxiproxy sidecar fallback inside
   * {@code CompositeConnectionChaos} lazy-spawns on the first verb that cannot be satisfied by
   * {@code libchaos-net}, so no extra annotation flag is required for that.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @RedisStandalone(enableConnectionChaos = true)
   * class ConnectionChaosTest {
   *
   *   @Test
   *   void simulatesConnectRefused(RedisConnectionInfo info, ControlFacade control) {
   *     // Per-syscall errno on connect()
   *     control.connection().rejectConnections(
   *         info.getContainer(), info.getHost() + ":" + info.getPort());
   *
   *     // Bandwidth shaping transparently falls through to Toxiproxy
   *     control.connection().limitBandwidth(
   *         info.getContainer(), info.getHost() + ":" + info.getPort(), 1024);
   *   }
   * }
   * }</pre>
   *
   * <p><strong>Requirements:</strong>
   *
   * <ul>
   *   <li>Linux container (the bundled {@code .so} variants cover glibc + musl on x86_64/arm64)
   *   <li>{@code macstab-chaos-connection} on the classpath (added via build dependency)
   * </ul>
   *
   * @return {@code true} to enable syscall-level connection chaos, {@code false} otherwise
   * @since 1.0
   */
  boolean enableConnectionChaos() default false;

  /**
   * Packages to install in the Redis container using the universal package manager.
   *
   * <p>Default: {@code []} (no additional packages)
   *
   * <p><strong>Purpose:</strong> Annotation-driven package installation for ANY Linux distribution,
   * automatically detecting the distribution and using the appropriate package manager (APT, APK,
   * DNF, YUM, PACMAN, ZYPPER).
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
   * // Install testing utilities
   * @RedisStandalone(packages = {"curl", "jq", "vim"})
   *
   * // Install network tools for chaos testing
   * @RedisStandalone(
   *   packages = {"iproute2", "iptables"},
   *   enableNetworkChaos = true
   * )
   *
   * // Multi-instance with different packages
   * @RedisStandalone(id = "primary", packages = {"redis-tools"})
   * @RedisStandalone(id = "cache", packages = {"curl", "jq"})
   * }</pre>
   *
   * <p><strong>Supported Distributions:</strong>
   *
   * <table border="1">
   *   <caption>Supported Linux distributions and package managers</caption>
   *   <tr><th>Distribution</th><th>Package Manager</th><th>Example</th></tr>
   *   <tr><td>Debian/Ubuntu</td><td>apt-get</td><td>redis:7.4</td></tr>
   *   <tr><td>Alpine</td><td>apk</td><td>redis:7.4-alpine</td></tr>
   *   <tr><td>Fedora/RHEL 8+</td><td>dnf</td><td>fedora:39</td></tr>
   *   <tr><td>CentOS 7</td><td>yum</td><td>centos:7</td></tr>
   *   <tr><td>Arch Linux</td><td>pacman</td><td>archlinux:latest</td></tr>
   *   <tr><td>openSUSE</td><td>zypper</td><td>opensuse/leap:15</td></tr>
   * </table>
   *
   * <p><strong>Installation Process:</strong>
   *
   * <ol>
   *   <li>Container starts
   *   <li>Extension detects Linux distribution from /etc/os-release
   *   <li>Selects appropriate package manager
   *   <li>Deduplicates package list
   *   <li>Runs package manager install command
   *   <li>Verifies installation with 'which' command
   * </ol>
   *
   * <p><strong>Performance:</strong>
   *
   * <ul>
   *   <li>Debian/Ubuntu: ~4-5 seconds (apt-get update + install)
   *   <li>Alpine: ~2-3 seconds (apk update + add)
   *   <li>Fedora: ~5-6 seconds (dnf install)
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
   * </ul>
   *
   * <p><strong>Best Practices:</strong>
   *
   * <pre>{@code
   * // ✅ Good: Install minimal packages needed for testing
   * @RedisStandalone(packages = {"curl", "jq"})
   *
   * // ❌ Bad: Installing too many packages slows tests
   * @RedisStandalone(packages = {"curl", "vim", "emacs", "htop", "git", ...})
   *
   * // ✅ Good: Combine with network chaos for comprehensive testing
   * @RedisStandalone(
   *   packages = {"iproute2", "iptables"},
   *   enableNetworkChaos = true
   * )
   * }</pre>
   *
   * @return array of package names (empty = no packages to install)
   * @since 1.0
   * @see com.macstab.chaos.core.util.PackageInstaller
   * @see com.macstab.chaos.core.util.PackageManager
   */
  String[] packages() default {};
}
