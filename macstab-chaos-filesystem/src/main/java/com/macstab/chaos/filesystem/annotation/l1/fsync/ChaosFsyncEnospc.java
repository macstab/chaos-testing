/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.fsync;

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
 * Injects {@code ENOSPC} into {@code fsync(2)}, causing the call to return {@code -1} with {@code
 * errno = ENOSPC} as if the kernel ran out of disk space while allocating journal blocks or
 * indirect blocks needed to flush the file's dirty data to the storage device.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code FSYNC}, errno = {@code ENOSPC})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * fsync} call; when it fires the interposer returns {@code -1} with {@code errno = ENOSPC} without
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
 *   <li>On each intercepted {@code fsync} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = ENOSPC}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOSPC} from {@code fsync} means the durability guarantee failed because the
 *       filesystem ran out of space while flushing metadata (journal blocks, indirect blocks,
 *       directory entries) that were deferred from the preceding write operations. The preceding
 *       writes appeared to succeed but are not durable. Assert that the application treats
 *       fsync-time {@code ENOSPC} with the same severity as write-time {@code ENOSPC}.
 *   <li>WAL implementations must handle {@code ENOSPC} on the WAL fsync by aborting all
 *       transactions that were waiting for this fsync's durability guarantee; assert that no
 *       transaction is acknowledged as committed when the WAL fsync failed due to disk exhaustion.
 *   <li>Journalled filesystems (ext4 with journalling, XFS) must flush journal blocks before
 *       flushing data blocks; an {@code ENOSPC} during journal flushing is returned as {@code
 *       ENOSPC} from {@code fsync}. Assert that the application does not assume the data is durable
 *       when only the writes (not the subsequent fsync) succeeded.
 *   <li>Assert that the application emits a "disk full — fsync failed" alert that is distinct from
 *       a write-time disk-full alert, enabling operators to identify that the filesystem is full
 *       and that recent writes may not be durable.
 * </ul>
 *
 * <p>In production, {@code ENOSPC} from {@code fsync} is less common than from {@code write} but
 * occurs on journalled filesystems when the journal fills up (because pending transactions have not
 * been checkpointed) and a new journal block allocation fails, and on copy-on-write filesystems
 * (Btrfs, ZFS) where the fsync triggers the allocation of new on-disk blocks for the CoW pages and
 * the allocation fails because the filesystem has no free blocks.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fsync(2)} flushes all dirty data and metadata for the file to the storage device. On a
 * journalled filesystem, this involves writing the dirty data pages, writing the updated metadata
 * to the journal, and waiting for the journal commit to be acknowledged by the device. Each step
 * may require allocating new blocks (journal blocks, indirect blocks), and any of these allocations
 * can fail with {@code ENOSPC} if the filesystem has no free blocks. The application sees a single
 * {@code ENOSPC} from the {@code fsync} call, even though the write calls that created the dirty
 * pages returned successfully.
 *
 * <p>This is the "deferred ENOSPC" scenario: the filesystem accepts writes into the page cache even
 * when it cannot immediately guarantee block allocation, deferring the allocation to the writeback
 * or fsync path. Applications that write data and then fsync to make it durable must handle {@code
 * ENOSPC} from the fsync, not just from the write, to correctly detect all disk-full conditions.
 *
 * <p>Java's {@code FileChannel.force(true)} calls {@code fsync(2)} and throws an {@code
 * IOException} with the message "No space left on device" when the underlying call returns {@code
 * ENOSPC}. Application code that catches {@code IOException} from {@code force()} must treat this
 * as a fatal durability failure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosFsyncEnospc(probability = 0.001)
 * class FsyncEnospcTest {
 *   @Test
 *   void diskFullOnFsyncAbortsTransactionAndAlertsOperators() {
 *     // assert that ENOSPC on fsync causes transaction abort and a disk-full alert
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosFsyncEio
 * @see ChaosWriteEnospc
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosFsyncEnospc.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.FSYNC, errno = Errno.ENOSPC)
public @interface ChaosFsyncEnospc {

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
   * @ChaosFsyncEnospc(id = "primary",  probability = 0.001)
   * @ChaosFsyncEnospc(id = "replica",  probability = 0.01)
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
    ChaosFsyncEnospc[] value();
  }
}
