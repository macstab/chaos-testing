/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Container factories for Redis standalone and Sentinel cluster deployments.
 *
 * <p>Contains:
 *
 * <ul>
 *   <li>{@link com.macstab.chaos.redis.factory.StandaloneContainerFactory} — plain Redis
 *   <li>{@link com.macstab.chaos.redis.factory.SentinelContainerFactory} — Sentinel clusters
 *   <li>{@link com.macstab.chaos.redis.factory.SentinelCommandBuilder} — Sentinel commands
 *   <li>{@link com.macstab.chaos.redis.extension.SentinelCluster} — cluster value object (lives in
 *       the {@code extension} package alongside the JUnit extension that exposes it)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
package com.macstab.chaos.redis.factory;
