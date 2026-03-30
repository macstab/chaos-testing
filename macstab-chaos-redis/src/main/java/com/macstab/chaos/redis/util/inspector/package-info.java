/* (C)2026 Christian Schnapka / Macstab GmbH */

/**
 * Connection-based Redis inspection utilities.
 *
 * <p>Tools in this package require a live Lettuce {@code RedisCommands} connection. They interact
 * with Redis directly to measure slow commands, detect connection leaks, analyze memory usage, and
 * verify replication consistency.
 *
 * <p>All tools use constructor injection and are testable with Mockito mocks.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
package com.macstab.chaos.redis.util.inspector;
