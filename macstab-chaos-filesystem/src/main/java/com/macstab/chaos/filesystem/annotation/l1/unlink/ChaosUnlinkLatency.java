/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.unlink;

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
 * Delays every {@code unlink(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making file deletion slower than the application expects
 * while still successfully removing the directory entry.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code UNLINK}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the file is deleted normally. No probability gate is applied;
 * the delay fires on every intercepted {@code unlink} call. No runtime operation-effect validation
 * is needed.
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
 *   <li>On each intercepted {@code unlink} call the interposer sleeps for {@link #delayMs} ms
 *       before issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>File deletion operations take longer than normal; applications that delete many temporary
 *       files in a tight loop (cleanup after batch processing, log archival deletion) will see
 *       increased cleanup latency. Assert that the cleanup operation's timeout accounts for the
 *       accumulated delay across all deleted files.
 *   <li>Applications that delete a file and then immediately create a new file at the same path (a
 *       "recreate" pattern) will see the window between deletion and creation extended by the
 *       injected delay; assert that the application handles concurrent access during this window
 *       rather than assuming the deletion is instantaneous.
 *   <li>Log rotation implementations that delete expired archive files may accumulate significant
 *       delay when deleting many archives; assert that the rotation timeout is calibrated for the
 *       worst-case number of deletions per rotation cycle.
 *   <li>Assert that slow unlink operations on a background cleanup thread do not block the
 *       application's main request processing path; cleanup should be asynchronous and should not
 *       hold any lock that the request processing path needs.
 * </ul>
 *
 * <p>In production, slow {@code unlink} calls occur on network filesystems (NFS, CIFS) where the
 * server must acknowledge the deletion before the client-side unlink returns, on HDD-backed
 * filesystems when the directory's data blocks must be read into the page cache before the entry
 * can be removed, and when the filesystem's free block bitmap must be updated for a large file and
 * the bitmap blocks are not cached.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code unlink(2)} removes a directory entry and decrements the inode's link count. The
 * directory modification requires writing the parent directory's data blocks (to remove the entry)
 * and updating the inode's metadata. For journalled filesystems, this also requires writing a
 * journal transaction. On a warm cache with a small directory, the operation completes in
 * microseconds. On a cold cache with a large directory (many entries per block), the operation
 * requires reading and writing directory blocks.
 *
 * <p>For a file with a single link (the common case), the inode and data blocks are freed after the
 * unlink if no file descriptors are open. Freeing large files requires walking and freeing many
 * data blocks, indirect blocks, and double-indirect blocks. This "deferred free" work can make
 * unlink slow for large files on filesystems without delayed deallocation.
 *
 * <p>Java's {@code Files.delete(Path)} and {@code File.delete()} both call {@code unlink(2)}. The
 * delay applies before the kernel call, so the calling thread blocks for the duration of the delay
 * plus the actual kernel operation time.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosUnlinkLatency(delayMs = 50)
 * class UnlinkLatencyTest {
 *   @Test
 *   void cleanupLoopCompletesWithinDeadlineUnderSlowDeletion() {
 *     // assert that batch file cleanup finishes within its timeout even when each unlink is slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosOpenLatency
 * @see ChaosRenameFromLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosUnlinkLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.UNLINK)
public @interface ChaosUnlinkLatency {

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
   * @ChaosUnlinkLatency(id = "primary",  probability = 0.001)
   * @ChaosUnlinkLatency(id = "replica",  probability = 0.01)
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
    ChaosUnlinkLatency[] value();
  }
}
