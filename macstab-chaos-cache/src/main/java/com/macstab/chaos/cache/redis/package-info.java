/* (C)2026 Christian Schnapka / Macstab GmbH */

/**
 * Redis cache chaos implementation.
 *
 * <p>Injects TCP-level faults via Toxiproxy and Redis-level faults via redis-cli. Entry point:
 * {@link com.macstab.chaos.cache.redis.RedisCacheChaosProvider}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.cache.redis;
