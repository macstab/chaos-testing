/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.extension.SentinelCluster;

/**
 * Factory that creates and fully starts a {@link SentinelCluster} from a {@link RedisSentinel}
 * annotation.
 *
 * <p><strong>Contract:</strong> Implementations must return a fully started cluster (all containers
 * running, extras installed). On failure, throw — the caller handles error wrapping.
 *
 * <p><strong>Design:</strong> Functional interface so tests can inject a mock lambda without a full
 * mock object, keeping test setup minimal.
 *
 * <p><strong>Production implementation:</strong> {@link DefaultSentinelClusterFactory}
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see DefaultSentinelClusterFactory
 * @see SentinelStartupOrchestrator
 */
@FunctionalInterface
public interface SentinelClusterFactory {

  /**
   * Creates and starts a Sentinel cluster from the given annotation configuration.
   *
   * @param annotation cluster annotation (never null)
   * @return fully started cluster (never null)
   * @throws Exception if cluster creation or startup fails
   */
  SentinelCluster create(RedisSentinel annotation) throws Exception;
}
