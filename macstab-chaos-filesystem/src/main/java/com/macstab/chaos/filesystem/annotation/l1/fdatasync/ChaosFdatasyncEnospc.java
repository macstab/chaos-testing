/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.fdatasync;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.annotation.l1.fsync.ChaosFsyncEnospc;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code ENOSPC} into {@code fdatasync(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOSPC} as if the kernel ran out of disk space while allocating blocks needed to
 * flush the file's dirty data pages to the storage device.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code FDATASYNC}, errno = {@code
 * ENOSPC}) tuple. A Bernoulli trial with probability {@link #probability} is run on each
 * intercepted {@code fdatasync} call; when it fires the interposer returns {@code -1} with {@code
 * errno = ENOSPC} without performing any real kernel operation. No runtime operation-errno
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
 *   <li>On each intercepted {@code fdatasync} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = ENOSPC}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOSPC} from {@code fdatasync} means the data durability guarantee failed because
 *       the filesystem ran out of space while flushing data blocks. The preceding write calls that
 *       appeared to succeed are not durable. Assert that the application treats fdatasync-time
 *       {@code ENOSPC} as a fatal durability failure and does not consider the data persisted.
 *   <li>Database engines that use {@code fdatasync} for WAL record durability must handle {@code
 *       ENOSPC} by aborting all transactions that were waiting for this fdatasync; assert that the
 *       engine does not acknowledge any commits when the fdatasync returned {@code ENOSPC}.
 *   <li>Applications that write to pre-allocated regions (using {@code fallocate} to reserve space
 *       before writing) expect that writes within the pre-allocated region will not encounter
 *       {@code ENOSPC} at the fdatasync stage. Assert that the pre-allocation is large enough to
 *       cover the entire write batch and that the application detects when the pre-allocation fails
 *       (rather than silently writing to unallocated space).
 *   <li>Assert that the application's error path on fdatasync {@code ENOSPC} triggers a disk-space
 *       alert and transitions to a read-only or error state, preventing new writes from
 *       accumulating unacknowledged data.
 * </ul>
 *
 * <p>In production, {@code ENOSPC} from {@code fdatasync} occurs on copy-on-write filesystems
 * (Btrfs, ZFS) where the fdatasync triggers the allocation of new on-disk blocks for CoW pages that
 * were deferred at write time, and on filesystems with delayed allocation where the block
 * allocation that was deferred from the write call is now performed during the fdatasync flush.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fdatasync(2)} flushes only the file's data pages and the minimum metadata needed to
 * access the data, making it faster than {@code fsync(2)} on journalled filesystems. However, on
 * filesystems with delayed block allocation (ext4 with {@code delalloc}, Btrfs), the actual block
 * allocation for data written to the page cache may be deferred until the fdatasync. When this
 * deferred allocation fails because the filesystem has no free blocks, the fdatasync returns {@code
 * ENOSPC} even though the preceding write calls succeeded.
 *
 * <p>This deferred allocation ENOSPC is the most common real-world scenario for fdatasync {@code
 * ENOSPC}: the application writes data, the filesystem accepts it into the page cache without
 * allocating blocks (relying on free space estimates that may be optimistic), and then the
 * fdatasync fails when the actual block allocation is attempted. Applications must handle this case
 * even if they check free disk space before writing.
 *
 * <p>Java's {@code FileChannel.force(false)} calls {@code fdatasync(2)} on Linux. When the
 * underlying call returns {@code ENOSPC}, Java throws an {@code IOException} with the message "No
 * space left on device". Application code must propagate this to the transaction layer.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosFdatasyncEnospc(probability = 0.001)
 * class FdatasyncEnospcTest {
 *   @Test
 *   void diskFullOnFdatasyncAbortsTransactionAndAlertsOperators() {
 *     // assert that ENOSPC on fdatasync causes transaction abort and a disk-full alert
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosFdatasyncEio
 * @see ChaosFsyncEnospc
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosFdatasyncEnospc.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.FDATASYNC, errno = Errno.ENOSPC)
public @interface ChaosFdatasyncEnospc {

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
   * @ChaosFdatasyncEnospc(id = "primary",  probability = 0.001)
   * @ChaosFdatasyncEnospc(id = "replica",  probability = 0.01)
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
    ChaosFdatasyncEnospc[] value();
  }
}
