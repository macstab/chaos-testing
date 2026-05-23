/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.allocate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Delays every {@code fallocate(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making disk pre-allocation slower than the application
 * expects while still successfully reserving the requested disk blocks.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ALLOCATE}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call after
 * the configured extra delay — the pre-allocation completes normally. No probability gate is applied;
 * the delay fires on every intercepted {@code fallocate} call. No runtime operation-effect validation
 * is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.IO)} on the container definition causes the
 *       extension to upload {@code libchaos-io.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code open}, {@code read}, {@code write}, {@code close},
 *       {@code fsync}, {@code fdatasync}, {@code truncate}, {@code unlink}, {@code rename}, and
 *       {@code fallocate} at the dynamic-linker level.
 *   <li>On each intercepted {@code fallocate} call the interposer sleeps for {@link #delayMs} ms
 *       before issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Applications that pre-allocate large WAL segment files at startup will see the startup time
 *       increase by the injected delay; assert that the application's startup deadline accounts for
 *       slow pre-allocation on congested storage rather than assuming it completes within a fixed
 *       budget.
 *   <li>Database engines that roll over WAL segments and pre-allocate the next segment while
 *       writing to the current segment will see the background pre-allocation thread delayed; assert
 *       that a slow pre-allocation does not block the foreground write path or cause transaction
 *       commits to stall waiting for the next segment to be ready.
 *   <li>Applications that pre-allocate temporary files before processing large batches will see the
 *       batch start time increase; assert that the batch processing timeout is measured from after
 *       the pre-allocation rather than from before it.
 *   <li>Assert that a slow {@code fallocate} on a background thread does not cause the main request
 *       path to time out; pre-allocation should always be performed asynchronously or with a timeout
 *       that falls back to writing without pre-allocation.
 * </ul>
 *
 * <p>In production, slow {@code fallocate} calls occur on storage systems under heavy write load
 * where the filesystem's block allocation bitmap is not cached and must be read from disk, on SAN
 * or NFS-backed filesystems where the allocation requires a round-trip to the storage server, and
 * on HDD-backed filesystems where the block allocation table is stored on a cold platter area
 * requiring a head seek before the allocation can be recorded.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fallocate(2)} modifies the file's on-disk block allocation by writing to the filesystem's
 * block allocation bitmap and the file's inode extent list, then commits these changes to the
 * journal. On a warm cache with low storage pressure, the journal commit completes in microseconds.
 * Under write pressure — many concurrent processes issuing metadata-heavy operations — the journal
 * commit queue can grow long, making even simple metadata operations like {@code fallocate} take
 * tens or hundreds of milliseconds.
 *
 * <p>The latency is proportional to the size of the requested allocation only when the block
 * allocator must initialise the allocated blocks (with {@code FALLOC_FL_ZERO_RANGE}) — for a
 * plain {@code fallocate} the kernel only records the block extents in the inode without touching
 * the data blocks, making the operation metadata-only and fast regardless of allocation size.
 * Slow plain {@code fallocate} indicates journal commit pressure, not a data write latency issue.
 *
 * <p>Java's NIO {@code FileChannel} does not expose {@code fallocate} directly. Applications
 * using JNI-wrapped native libraries (SQLite, RocksDB, LMDB via JNI) that internally call
 * {@code fallocate} will see the injected delay surface as blocking time in the JNI call. The JVM's
 * own file I/O path does not call {@code fallocate}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosAllocateLatency(delayMs = 200)
 * class AllocateLatencyTest {
 *   @Test
 *   void walSegmentPreallocationDoesNotBlockForegroundWriteUnderStoragePressure() {
 *     // assert that a slow fallocate on the segment-roller thread does not stall commits
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAllocateEnospc
 * @see ChaosTruncateLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosAllocateLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.ALLOCATE)
public @interface ChaosAllocateLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 50L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-io
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosAllocateLatency(id = "primary",  probability = 0.001)
   * @ChaosAllocateLatency(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosAllocateLatency[] value();
  }
}
