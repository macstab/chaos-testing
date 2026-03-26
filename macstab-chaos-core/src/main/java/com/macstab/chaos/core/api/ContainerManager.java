/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Type-safe manager for programmatic container access.
 *
 * <p><strong>Purpose:</strong> Provides a type-safe facade for accessing chaos testing containers.
 * Used by annotation INSTANCE fields to provide convenient programmatic access.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * @RedisStandalone
 * public @interface RedisStandalone {
 *   ContainerManager<StandaloneRedis> INSTANCE =
 *     new ContainerManager<>(
 *       id -> ChaosContainers.get(RedisStandalone.class, id),
 *       () -> ChaosContainers.getAll(RedisStandalone.class)
 *     );
 * }
 *
 * // Usage
 * StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> Immutable after construction. Delegates to thread-safe
 * backend ({@link ChaosContainers}).
 *
 * @param <T> connection info type
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class ContainerManager<T> {

  private final Function<String, T> getter;
  private final Supplier<List<T>> allGetter;

  /**
   * Creates a container manager with custom access functions.
   *
   * @param getter function to get connection info by id (not null)
   * @param allGetter function to get all connection info objects (not null)
   */
  public ContainerManager(final Function<String, T> getter, final Supplier<List<T>> allGetter) {
    this.getter = Objects.requireNonNull(getter, "getter");
    this.allGetter = Objects.requireNonNull(allGetter, "allGetter");
  }

  /**
   * Gets connection info by container id.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
   * }</pre>
   *
   * @param id container id (specified in annotation)
   * @return connection info object (not null)
   * @throws IllegalStateException if no extension active
   * @throws java.util.NoSuchElementException if container not found
   */
  public T get(final String id) {
    return getter.apply(id);
  }

  /**
   * Gets all connection info objects.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * List<StandaloneRedis> all = RedisStandalone.INSTANCE.getAll();
   * }</pre>
   *
   * @return list of connection info objects (empty if none, never null)
   * @throws IllegalStateException if no extension active
   */
  public List<T> getAll() {
    return allGetter.get();
  }
}
