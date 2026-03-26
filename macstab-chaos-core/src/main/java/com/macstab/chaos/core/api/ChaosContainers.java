/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.NoSuchElementException;

import com.macstab.chaos.core.extension.ChaosTestingExtension;

/**
 * Universal programmatic access to chaos testing containers.
 *
 * <p><strong>Purpose:</strong> Provides type-safe access to container connection info by annotation
 * type or base interface type. Works with any chaos testing container (Redis, Postgres, Mongo,
 * etc.).
 *
 * <p><strong>Access Patterns:</strong>
 *
 * <pre>{@code
 * // By annotation type (type-safe)
 * StandaloneRedis cache = ChaosContainers.get(RedisStandalone.class, "cache");
 *
 * // By base interface (unified)
 * Redis any = ChaosContainers.get(Redis.class, "cache");
 *
 * // All instances of annotation type
 * List<StandaloneRedis> standalone = ChaosContainers.getAll(RedisStandalone.class);
 *
 * // All instances implementing interface
 * List<Redis> allRedis = ChaosContainers.getAll(Redis.class);
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> ThreadLocal storage per test class (JUnit 5 isolation).
 *
 * <p><strong>Availability:</strong> Only works inside {@code @Test} methods with active {@link
 * ChaosTestingExtension}. Throws {@link IllegalStateException} if called outside test context.
 *
 * <p><strong>Typical Usage:</strong> Most users should use per-annotation INSTANCE fields (e.g.,
 * {@code RedisStandalone.INSTANCE.get("cache")}) rather than calling this API directly. This class
 * is primarily for framework-internal use and advanced scenarios.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see ChaosTestingExtension
 */
public final class ChaosContainers {

  private ChaosContainers() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Gets connection info for specific container instance by annotation type.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * StandaloneRedis cache = ChaosContainers.get(RedisStandalone.class, "cache");
   * }</pre>
   *
   * @param <T> connection info type
   * @param type annotation class (e.g., {@code RedisStandalone.class})
   * @param id container id (specified in annotation)
   * @return connection info object
   * @throws IllegalStateException if no extension active
   * @throws NoSuchElementException if container not found
   */
  @SuppressWarnings("unchecked")
  public static <T> T get(final Class<? extends Annotation> type, final String id) {
    return (T) ChaosTestingExtension.getConnectionInfo(type, id);
  }

  /**
   * Gets all connection info objects for annotation type.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * List<StandaloneRedis> all = ChaosContainers.getAll(RedisStandalone.class);
   * }</pre>
   *
   * @param <T> connection info type
   * @param type annotation class
   * @return list of connection info objects (empty if none)
   * @throws IllegalStateException if no extension active
   */
  @SuppressWarnings("unchecked")
  public static <T> List<T> getAll(final Class<? extends Annotation> type) {
    return (List<T>) ChaosTestingExtension.getAllConnectionInfo(type);
  }

  /**
   * Gets all connection info objects implementing a base interface.
   *
   * <p><strong>Purpose:</strong> Retrieves all containers regardless of specific annotation type,
   * as long as they implement the base interface.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Get ALL Redis instances (standalone, sentinel, cluster)
   * List<Redis> allRedis = ChaosContainers.getAll(Redis.class);
   * }</pre>
   *
   * @param <T> base interface type
   * @param baseType base interface class (e.g., {@code Redis.class})
   * @return list of all connection info objects implementing base type (empty if none)
   * @throws IllegalStateException if no extension active
   */
  @SuppressWarnings("unchecked")
  public static <T> List<T> getAllByBaseType(final Class<T> baseType) {
    return (List<T>) ChaosTestingExtension.getAllConnectionInfoByBaseType(baseType);
  }

  /**
   * Gets connection info by id, searching all subtypes of base interface.
   *
   * <p><strong>Purpose:</strong> Unified access when you know the id but not the specific topology.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Get Redis by id (could be standalone, sentinel, or cluster)
   * Redis cache = ChaosContainers.getByBaseType(Redis.class, "cache");
   * }</pre>
   *
   * @param <T> base interface type
   * @param baseType base interface class
   * @param id container id
   * @return connection info object
   * @throws IllegalStateException if no extension active
   * @throws NoSuchElementException if container not found
   */
  @SuppressWarnings("unchecked")
  public static <T> T getByBaseType(final Class<T> baseType, final String id) {
    return (T) ChaosTestingExtension.getConnectionInfoByBaseType(baseType, id);
  }
}
