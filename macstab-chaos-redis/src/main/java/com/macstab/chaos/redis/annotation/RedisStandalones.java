/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import com.macstab.chaos.redis.extension.RedisContainerExtension;

/**
 * Container annotation for multiple {@link RedisStandalone} instances on a single test class.
 *
 * <p>This annotation is <strong>automatically applied</strong> by the Java compiler when you use
 * multiple {@code @RedisStandalone} annotations (Java 8+ repeatable annotation pattern). You
 * typically don't use this annotation directly.
 *
 * <p><strong>Automatic Transformation:</strong>
 *
 * <pre>{@code
 * // You write:
 * @RedisStandalone(id = "cache", version = "7.4")
 * @RedisStandalone(id = "session", version = "7.2")
 * class Test { }
 *
 * // Compiler transforms to:
 * @RedisStandalones(value = {
 *   @RedisStandalone(id = "cache", version = "7.4"),
 *   @RedisStandalone(id = "session", version = "7.2")
 * })
 * class Test { }
 * }</pre>
 *
 * <p><strong>Multi-Instance Capabilities:</strong>
 *
 * <ul>
 *   <li>✅ Start 1-5 standalone Redis instances per test class (resource budget enforced)
 *   <li>✅ Parallel instance startup (faster than sequential)
 *   <li>✅ Inject all instances via {@code List<RedisConnectionInfo>} parameter
 *   <li>✅ Access by ID programmatically: {@code RedisStandalone.INSTANCE.get("id")}
 *   <li>✅ Works on all platforms (Linux, macOS, Windows)
 * </ul>
 *
 * <p><strong>Resource Budget:</strong> Maximum 5 standalone instances or 20 total containers per
 * test class. Exceeding this limit throws {@code ResourceBudgetExceededException} at startup.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * @RedisStandalone(id = "cache", version = "7.4")
 * @RedisStandalone(id = "session", version = "7.2")
 * @RedisStandalone(id = "rate-limiter", version = "7.4")
 * @SpringBootTest
 * class MultiInstanceTest {
 *
 *   // Option 1: Inject all instances (order matches declaration)
 *   @BeforeAll
 *   static void setupAll(List<RedisConnectionInfo> instances) {
 *     assertThat(instances).hasSize(3);
 *     // instances.get(0) == "cache"
 *     // instances.get(1) == "session"
 *     // instances.get(2) == "rate-limiter"
 *   }
 *
 *   // Option 2: Access by ID
 *   @Test
 *   void testCache() {
 *     RedisConnectionInfo cache = RedisStandalone.INSTANCE.get("cache");
 *     assertThat(cache.getPort()).isGreaterThan(0);
 *   }
 *
 *   // Option 3: Get all programmatically
 *   @Test
 *   void testAll() {
 *     List<RedisConnectionInfo> all = RedisStandalone.INSTANCE.getAll();
 *     assertThat(all).hasSize(3);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Mixed Topology Example (Sentinel + Standalone):</strong>
 *
 * <pre>{@code
 * @RedisSentinel(id = "ha-cluster", replicas = 2, sentinels = 3)
 * @RedisStandalone(id = "cache")
 * @RedisStandalone(id = "session")
 * class MixedTopologyTest {
 *
 *   @BeforeAll
 *   static void setupMixed(
 *       List<SentinelCluster> sentinels,
 *       List<RedisConnectionInfo> standalones
 *   ) {
 *     assertThat(sentinels).hasSize(1);
 *     assertThat(standalones).hasSize(2);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Performance:</strong>
 *
 * <ul>
 *   <li>3 instances (parallel): 2-3s startup
 *   <li>5 instances (parallel): 3-4s startup
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see RedisStandalone
 * @see RedisContainerExtension
 * @since 1.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(RedisContainerExtension.class)
public @interface RedisStandalones {

  /**
   * Array of standalone Redis configurations.
   *
   * <p><strong>Note:</strong> This is populated automatically by the compiler when using multiple
   * {@code @RedisStandalone} annotations. You typically don't set this value directly.
   *
   * @return array of standalone Redis configurations
   */
  RedisStandalone[] value();
}
