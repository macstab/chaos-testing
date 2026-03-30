/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Failover simulation for Redis Sentinel clusters.
 *
 * <p>Contains {@link com.macstab.chaos.redis.control.failover.FailoverHelper} which drives master
 * election testing by killing the current master and measuring election latency.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
package com.macstab.chaos.redis.control.failover;
