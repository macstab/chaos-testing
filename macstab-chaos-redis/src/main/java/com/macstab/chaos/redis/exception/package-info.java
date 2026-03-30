/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Redis chaos testing exception hierarchy (sealed).
 *
 * <p>Root: {@link com.macstab.chaos.redis.exception.ClusterException} (sealed abstract). Permitted
 * subclasses: {@link com.macstab.chaos.redis.exception.ClusterCreationException}, {@link
 * com.macstab.chaos.redis.exception.ClusterStartupException}, {@link
 * com.macstab.chaos.redis.exception.ClusterTopologyException}, {@link
 * com.macstab.chaos.redis.exception.FailoverException}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
package com.macstab.chaos.redis.exception;
