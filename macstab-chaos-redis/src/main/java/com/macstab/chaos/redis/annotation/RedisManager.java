/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.annotation;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Generic manager for Redis test containers (standalone or Sentinel).
 *
 * <p>Provides programmatic access to containers started by {@link RedisStandalone} or {@link
 * RedisSentinel} annotations. Supports both single-instance and multi-instance scenarios.
 *
 * <p><strong>Single Instance Usage (v1.x compatible):</strong>
 *
 * <pre>{@code
 * @RedisStandalone(id = "master")
 * class MyTest {
 *   @BeforeEach
 *   void setUp() {
 *     final var redis = RedisStandalone.INSTANCE.get("master");
 *     // redis.getHost(), redis.getPort()
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Multi-Instance Usage (v2.0+):</strong>
 *
 * <pre>{@code
 * @RedisSentinel(id = "primary", replicas = 3)
 * @RedisSentinel(id = "secondary", replicas = 2)
 * class MultiClusterTest {
 *
 *   @Test
 *   void testAllClusters() {
 *     // Get all clusters in declaration order
 *     List<SentinelCluster> all = RedisSentinel.INSTANCE.getAll();
 *     assertThat(all).hasSize(2);
 *     // all.get(0) corresponds to id="primary"
 *     // all.get(1) corresponds to id="secondary"
 *   }
 *
 *   @Test
 *   void testSpecificCluster() {
 *     // Access by ID
 *     SentinelCluster primary = RedisSentinel.INSTANCE.get("primary");
 *     assertThat(primary.getReplicas()).hasSize(3);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> This class delegates to extension-managed ThreadLocal
 * contexts. Safe for parallel test execution across different test classes. NOT safe for concurrent
 * access within the same test class (undefined behavior).
 *
 * @param <T> container info type (RedisConnectionInfo or SentinelCluster)
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class RedisManager<T> {

  private final Function<String, T> containerAccessor;
  private final Supplier<List<T>> allContainersAccessor;

  /**
   * Package-private constructor (only called from annotations).
   *
   * @param containerAccessor function to retrieve container by ID (must not be null)
   * @param allContainersAccessor supplier to retrieve all containers in order (must not be null)
   */
  RedisManager(
      final Function<String, T> containerAccessor, final Supplier<List<T>> allContainersAccessor) {
    this.containerAccessor = Objects.requireNonNull(containerAccessor, "containerAccessor");
    this.allContainersAccessor =
        Objects.requireNonNull(allContainersAccessor, "allContainersAccessor");
  }

  /**
   * Get default container (ID = "default").
   *
   * <p>Convenience for {@code get("default")}.
   *
   * <p><strong>Behavior with single instance:</strong> If only ONE instance exists (regardless of
   * ID), returns that instance. This provides backward compatibility and convenience for simple
   * test scenarios.
   *
   * <p><strong>Example (single instance, any ID):</strong>
   *
   * <pre>{@code
   * @RedisSentinel(id = "my-cluster")  // Not "default", but only one cluster
   * class Test {
   *   @Test
   *   void test() {
   *     SentinelCluster cluster = RedisSentinel.INSTANCE.get();  // Works!
   *   }
   * }
   * }</pre>
   *
   * @return container info (never null)
   * @throws IllegalStateException if no container started
   * @throws IllegalArgumentException if multiple containers exist without "default" ID
   */
  public T get() {
    return get("default");
  }

  /**
   * Get container by ID.
   *
   * <p><strong>Special "default" ID Handling:</strong> When requesting ID "default", the manager
   * uses smart resolution:
   *
   * <ol>
   *   <li>If only ONE container exists (regardless of ID), returns it
   *   <li>If multiple containers exist, requires explicit {@code id="default"} annotation
   * </ol>
   *
   * <p>This provides backward compatibility while supporting multi-instance scenarios.
   *
   * <p><strong>Example (multiple instances):</strong>
   *
   * <pre>{@code
   * @RedisSentinel(id = "default")  // Explicit "default" ID required
   * @RedisSentinel(id = "secondary")
   * class Test {
   *   @Test
   *   void test(SentinelCluster cluster) {  // Injects "default"
   *     // ...
   *   }
   * }
   * }</pre>
   *
   * @param id container ID from annotation (e.g., {@code @RedisStandalone(id = "master")})
   * @return container info (never null)
   * @throws IllegalArgumentException if ID not found
   * @throws IllegalStateException if called outside test context
   */
  public T get(final String id) {
    return containerAccessor.apply(id);
  }

  /**
   * Get all containers in annotation declaration order.
   *
   * <p><strong>Ordering Guarantee:</strong> Containers are returned in the same order they were
   * declared on the test class (top-to-bottom). This provides predictable iteration for
   * multi-instance scenarios.
   *
   * <p><strong>Performance:</strong> O(n) where n = number of annotations. Results are computed on
   * each call (not cached).
   *
   * <p><strong>Thread Safety:</strong> Safe for concurrent calls from different test classes (uses
   * ThreadLocal context). NOT safe for concurrent calls within same test class (undefined
   * behavior).
   *
   * <p><strong>Example (ordering):</strong>
   *
   * <pre>{@code
   * @RedisSentinel(id = "first")
   * @RedisSentinel(id = "second")
   * @RedisSentinel(id = "third")
   * class Test {
   *   @Test
   *   void example() {
   *     List<SentinelCluster> all = RedisSentinel.INSTANCE.getAll();
   *     // all.get(0) corresponds to id="first"
   *     // all.get(1) corresponds to id="second"
   *     // all.get(2) corresponds to id="third"
   *
   *     // NOT sorted by ID (preserves declaration order)
   *   }
   * }
   * }</pre>
   *
   * <p><strong>Example (single instance):</strong>
   *
   * <pre>{@code
   * @RedisSentinel  // Only one cluster
   * class Test {
   *   @Test
   *   void example() {
   *     List<SentinelCluster> all = RedisSentinel.INSTANCE.getAll();
   *     assertThat(all).hasSize(1);  // Single-element list
   *   }
   * }
   * }</pre>
   *
   * @return unmodifiable list of all instances in declaration order (never null, may be empty if
   *     called outside test context)
   * @throws IllegalStateException if called outside test context (behavior depends on extension
   *     implementation)
   * @since 2.0
   */
  public List<T> getAll() {
    return allContainersAccessor.get();
  }
}
