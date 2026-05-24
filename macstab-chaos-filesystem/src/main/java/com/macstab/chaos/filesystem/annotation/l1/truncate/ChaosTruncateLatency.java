/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.truncate;

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
 * Delays every {@code truncate(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making file size modification slower than the application
 * expects while still completing the operation normally.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code TRUNCATE}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the truncate completes successfully. No probability gate is
 * applied; the delay fires on every intercepted {@code truncate} call. No runtime operation-effect
 * validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.IO)} on the container definition causes the extension
 *       to upload {@code libchaos-io.so} into the container and prepend it to {@code LD_PRELOAD}
 *       before the process starts.
 *   <li>The shared library interposes {@code open}, {@code read}, {@code write}, {@code close},
 *       {@code fsync}, {@code fdatasync}, {@code truncate}, {@code unlink}, {@code rename}, and
 *       {@code fallocate} at the dynamic-linker level.
 *   <li>On each intercepted {@code truncate} call the interposer sleeps for {@link #delayMs} ms
 *       before issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>File size modification operations take longer than normal; applications that use {@code
 *       truncate} in the startup path (pre-sizing WAL files, sizing mmap files) will see increased
 *       startup latency. Assert that the application's startup timeout accounts for the injected
 *       delay.
 *   <li>Log rotation implementations that use {@code truncate} to clear the current log file after
 *       archiving will see increased rotation latency; assert that the rotation operation does not
 *       time out and that the logging path continues to function during a slow rotation.
 *   <li>Applications that use {@code truncate} on the critical path (e.g., truncating a temporary
 *       file before writing a new version of a configuration) will see increased operation latency;
 *       assert that the operation's timeout accounts for the truncate delay.
 *   <li>Assert that a slow {@code truncate} on the WAL pre-allocation path does not block the
 *       database from accepting connections — the pre-allocation should complete before the
 *       connection listener is activated, and the startup timeout should be calibrated accordingly.
 * </ul>
 *
 * <p>In production, slow {@code truncate} calls occur on NFS mounts when the server must update the
 * inode and release the blocks atomically while holding a lock, when the filesystem's free block
 * bitmap must be updated and the bitmap blocks are not in the page cache, and when a shrink
 * operation requires walking and freeing a large number of indirect blocks.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code truncate(2)} modifies the file's size by updating the inode's size field and optionally
 * allocating or freeing data blocks. A shrink operation must free the blocks that back the
 * truncated region, which requires walking the file's block map (direct blocks, indirect blocks,
 * double-indirect blocks) and updating the free block bitmap. For very large files, this can
 * require many disk accesses. An extend operation must update the inode's size field (and possibly
 * allocate blocks for the extended region on non-sparse filesystems).
 *
 * <p>This injection adds the delay before the kernel call, simulating the scheduling stall and
 * metadata I/O latency without requiring actual slow storage or large file structures. The delay
 * fires on every truncate call regardless of whether the operation shrinks or extends the file.
 *
 * <p>Java's {@code FileChannel.truncate(long)} calls {@code ftruncate(2)} and is affected by this
 * annotation when the underlying truncate call is intercepted. The delay applies before the kernel
 * call, so the calling thread blocks for the duration of the delay plus the actual kernel operation
 * time.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosTruncateLatency(delayMs = 100)
 * class TruncateLatencyTest {
 *   @Test
 *   void databaseStartupCompletesWithinDeadlineUnderSlowWalPreAllocation() {
 *     // assert that startup finishes within its deadline even when WAL truncate is slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAllocateLatency
 * @see ChaosOpenLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosTruncateLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.TRUNCATE)
public @interface ChaosTruncateLatency {

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
   * @ChaosTruncateLatency(id = "primary",  probability = 0.001)
   * @ChaosTruncateLatency(id = "replica",  probability = 0.01)
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
    ChaosTruncateLatency[] value();
  }
}
