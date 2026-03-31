/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Strategy abstraction for executing Redis commands in inspector tools.
 *
 * <p>Decouples {@link com.macstab.chaos.redis.util.inspector.SlowCommandDetector}, {@link
 * com.macstab.chaos.redis.util.inspector.ConnectionLeakTracker}, and {@link
 * com.macstab.chaos.redis.util.inspector.MemorySnapshotAnalyzer} from any specific Redis client or
 * container runtime.
 *
 * <p>Choose the backend that matches your test context:
 *
 * <ul>
 *   <li>{@link com.macstab.chaos.redis.util.inspector.executor.ShellRedisCommandExecutor} —
 *       container-backed, no Lettuce required, works in any topology
 *   <li>{@link com.macstab.chaos.redis.util.inspector.executor.LettuceRedisCommandExecutor} —
 *       reuses an existing Lettuce connection
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
package com.macstab.chaos.redis.util.inspector.executor;
