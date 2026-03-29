/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import com.macstab.chaos.redis.annotation.RedisStandalone;

/**
 * Factory that creates, starts, and configures a single standalone Redis container from a {@link
 * RedisStandalone} annotation.
 *
 * <p><strong>Contract:</strong> Implementations must return a {@link StartupResult} — either {@link
 * StartupResult.Success} or {@link StartupResult.Failure}. Must never throw; all exceptions must be
 * caught and wrapped in {@link StartupResult.Failure}.
 *
 * <p><strong>Design:</strong> Functional interface so tests can inject a mock lambda. The {@code
 * StartupResult} return (vs throwing) means the factory is self-contained and the orchestrator does
 * not need a try/catch around the factory call.
 *
 * <p><strong>Production implementation:</strong> {@link DefaultStandaloneContainerInstanceFactory}
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see DefaultStandaloneContainerInstanceFactory
 * @see StandaloneStartupOrchestrator
 */
@FunctionalInterface
public interface StandaloneContainerInstanceFactory {

  /**
   * Creates, starts, and configures a Redis container from the given annotation.
   *
   * @param annotation instance annotation (never null)
   * @return typed result — Success with started container, or Failure with error details
   */
  StartupResult create(RedisStandalone annotation);
}
