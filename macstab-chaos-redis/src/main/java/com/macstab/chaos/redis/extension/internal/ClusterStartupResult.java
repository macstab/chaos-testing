/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import com.macstab.chaos.redis.extension.SentinelCluster;

/**
 * Result of a single Sentinel cluster startup attempt.
 *
 * <p>Sealed: either {@link Success} or {@link Failure} — eliminates null-checks on result fields.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * ClusterStartupResult result = startSingleCluster(annotation);
 * switch (result) {
 *   case ClusterStartupResult.Success s -> clusters.put(s.clusterId(), s.cluster());
 *   case ClusterStartupResult.Failure f -> failures.add(new ClusterStartupFailure(
 *       f.clusterId(), f.errorMessage(), f.cause()));
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public sealed interface ClusterStartupResult
    permits ClusterStartupResult.Success, ClusterStartupResult.Failure {

  /**
   * Returns the cluster ID associated with this result.
   *
   * @return cluster ID (never null)
   */
  String clusterId();

  /**
   * Successful cluster startup result.
   *
   * @param clusterId cluster ID from {@code @RedisSentinel(id = "...")}
   * @param cluster the started cluster
   */
  record Success(String clusterId, SentinelCluster cluster) implements ClusterStartupResult {}

  /**
   * Failed cluster startup result.
   *
   * @param clusterId cluster ID from {@code @RedisSentinel(id = "...")}
   * @param errorMessage human-readable error description
   * @param cause original exception
   */
  record Failure(String clusterId, String errorMessage, Exception cause)
      implements ClusterStartupResult {}
}
