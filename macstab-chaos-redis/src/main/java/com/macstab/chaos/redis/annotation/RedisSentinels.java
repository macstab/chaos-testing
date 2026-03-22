/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import com.macstab.chaos.network.condition.DisabledOnNonLinuxHost;
import com.macstab.chaos.redis.extension.SentinelContainerExtension;

/**
 * Container annotation for multiple {@link RedisSentinel} clusters on a single test class.
 *
 * <p>This annotation is <strong>automatically applied</strong> by the Java compiler when you use
 * multiple {@code @RedisSentinel} annotations (Java 8+ repeatable annotation pattern). You
 * typically don't use this annotation directly.
 *
 * <p><strong>Automatic Transformation:</strong>
 *
 * <pre>{@code
 * // You write:
 * @RedisSentinel(id = "cluster-a")
 * @RedisSentinel(id = "cluster-b")
 * class Test { }
 *
 * // Compiler transforms to:
 * @RedisSentinels(value = {
 *   @RedisSentinel(id = "cluster-a"),
 *   @RedisSentinel(id = "cluster-b")
 * })
 * class Test { }
 * }</pre>
 *
 * <p><strong>Multi-Instance Capabilities:</strong>
 *
 * <ul>
 *   <li>✅ Start 1-3 Sentinel clusters per test class (resource budget enforced)
 *   <li>✅ Parallel cluster startup (40-50% faster than sequential)
 *   <li>✅ Inject all clusters via {@code List<SentinelCluster>} parameter
 *   <li>✅ Access by ID programmatically: {@code RedisSentinel.INSTANCE.get("id")}
 *   <li>✅ Automatic cleanup on failure (no resource leaks)
 * </ul>
 *
 * <p><strong>Resource Budget:</strong> Maximum 3 Sentinel clusters or 20 total containers per test
 * class. Exceeding this limit throws {@code ResourceBudgetExceededException} at startup.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * @RedisSentinel(id = "primary", replicas = 3, sentinels = 5, quorum = 3)
 * @RedisSentinel(id = "secondary", replicas = 2, sentinels = 3, quorum = 2)
 * @SpringBootTest
 * class MultiClusterTest {
 *
 *   // Option 1: Inject all clusters (order matches declaration)
 *   @BeforeAll
 *   static void setupAll(List<SentinelCluster> clusters) {
 *     assertThat(clusters).hasSize(2);
 *     // clusters.get(0) == "primary"
 *     // clusters.get(1) == "secondary"
 *   }
 *
 *   // Option 2: Access by ID
 *   @Test
 *   void testPrimary() {
 *     SentinelCluster primary = RedisSentinel.INSTANCE.get("primary");
 *     assertThat(primary.getSentinels()).hasSize(5);
 *   }
 *
 *   // Option 3: Get all programmatically
 *   @Test
 *   void testAll() {
 *     List<SentinelCluster> all = RedisSentinel.INSTANCE.getAll();
 *     assertThat(all).hasSize(2);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Platform Requirements:</strong> Linux host or dev container (native Docker
 * networking). Tests automatically skipped on macOS/Windows hosts.
 *
 * <p><strong>Performance:</strong>
 *
 * <ul>
 *   <li>2 clusters (parallel): 5-7s startup (vs 10s sequential)
 *   <li>3 clusters (parallel): 7-10s startup (vs 15s sequential)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see RedisSentinel
 * @see SentinelContainerExtension
 * @since 2.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(SentinelContainerExtension.class)
@DisabledOnNonLinuxHost(
    "Redis Sentinel tests require native Docker networking (Linux host or dev container)")
public @interface RedisSentinels {

  /**
   * Array of Sentinel cluster configurations.
   *
   * <p><strong>Note:</strong> This is populated automatically by the compiler when using multiple
   * {@code @RedisSentinel} annotations. You typically don't set this value directly.
   *
   * @return array of Sentinel cluster configurations
   */
  RedisSentinel[] value();
}
