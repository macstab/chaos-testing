/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.api;

/**
 * Redis deployment topology types.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public enum RedisTopology {
  /** Single Redis instance (no replication). */
  STANDALONE,

  /** Master-replica with sentinel monitoring (high availability). */
  SENTINEL,

  /** Sharded cluster (horizontal scaling). */
  CLUSTER
}
