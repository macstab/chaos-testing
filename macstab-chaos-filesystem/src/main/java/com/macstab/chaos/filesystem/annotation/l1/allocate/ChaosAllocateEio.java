/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.allocate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code EIO} into {@code fallocate(2)}, causing the call to return {@code -1} with {@code
 * errno = EIO} as if the storage device returned an I/O error while the kernel attempted to
 * allocate and record disk blocks for the file's pre-allocated region.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ALLOCATE}, errno = {@code EIO})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * fallocate} call; when it fires the interposer returns {@code -1} with {@code errno = EIO} without
 * performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code fallocate} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EIO}, simulating a storage device error during block pre-allocation.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EIO} from {@code fallocate} means the storage device failed while attempting to
 *       record the block allocation; the file's size and content are unchanged and no blocks have
 *       been reserved. Assert that the application does not attempt to write to blocks it assumed
 *       were pre-allocated after the {@code fallocate} returned an error.
 *   <li>Database engines that use {@code fallocate} to pre-allocate WAL segment files must handle
 *       {@code EIO} at segment creation time; assert that the failure causes the database to retry
 *       with a fallback allocation strategy (write zeroes instead of fallocate) or to abort the
 *       segment creation and report a fatal storage error.
 *   <li>Applications that pre-allocate large output files before writing must treat {@code EIO} as
 *       a hardware failure indication; assert that the application propagates this as a fatal error
 *       rather than silently proceeding without pre-allocation.
 *   <li>Assert that {@code EIO} from {@code fallocate} triggers the same storage-failure handler as
 *       {@code EIO} from write or fsync — all three indicate the same underlying hardware problem
 *       and should produce consistent operator alerts.
 * </ul>
 *
 * <p>In production, {@code EIO} from {@code fallocate} occurs when the storage device returns a
 * hard error during the journal transaction that records the block allocation (the metadata write
 * for the block-allocation bitmap update), when the filesystem detects an inconsistency in its
 * free-block bitmap that prevents safe allocation, and when the storage backend (SAN, network
 * storage) drops the connection mid-transaction.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fallocate(2)} with no flags (or with {@code FALLOC_FL_KEEP_SIZE}) extends a file's
 * block allocation without changing the file's content. The kernel must update the filesystem's
 * block allocation bitmap and the file's inode (to record the new block extents), and commit a
 * journal transaction recording these metadata changes. If any of these disk writes fail — the
 * bitmap update, the inode update, or the journal commit — the kernel returns {@code EIO}.
 *
 * <p>When {@code fallocate} returns {@code EIO}, the allocation may be partially committed to the
 * journal but not yet applied to the filesystem's metadata. The filesystem's journal recovery
 * mechanism (replayed at the next mount) will either complete or roll back the partial transaction;
 * the file is left in a consistent state (either fully allocated or not allocated at all). The
 * application cannot distinguish between "partially allocated" and "not allocated" at the time of
 * the error and must treat the file as if no blocks were reserved.
 *
 * <p>Java's NIO {@code FileChannel} does not expose {@code fallocate} directly. Applications that
 * use {@code fallocate} through JNI wrappers (database native libraries, file management utilities)
 * will see the error surfaced as an {@code IOException} by the JNI bridge. The JVM itself does not
 * use {@code fallocate} for standard file I/O operations.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosAllocateEio(probability = 0.001)
 * class AllocateEioTest {
 *   @Test
 *   void walPreallocationEioTriggersStorageFailureAlert() {
 *     // assert that EIO on fallocate causes the application to report a fatal storage error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAllocateEnospc
 * @see ChaosWriteEio
 * @see ChaosFsyncEio
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosAllocateEio.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.ALLOCATE, errno = Errno.EIO)
public @interface ChaosAllocateEio {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

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
   * @ChaosAllocateEio(id = "primary",  probability = 0.001)
   * @ChaosAllocateEio(id = "replica",  probability = 0.01)
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
    ChaosAllocateEio[] value();
  }
}
