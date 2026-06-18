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
import com.macstab.chaos.filesystem.annotation.l1.fsync.ChaosFsyncEnospc;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteEnospc;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code ENOSPC} into {@code fallocate(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOSPC} as if the filesystem has no free disk blocks to satisfy the requested
 * pre-allocation and cannot guarantee space for future writes to the allocated region.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ALLOCATE}, errno = {@code
 * ENOSPC}) tuple. A Bernoulli trial with probability {@link #probability} is run on each
 * intercepted {@code fallocate} call; when it fires the interposer returns {@code -1} with {@code
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
 *   <li>On each intercepted {@code fallocate} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = ENOSPC}, simulating a disk-full condition at pre-allocation time.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOSPC} from {@code fallocate} means the filesystem could not reserve the requested
 *       disk blocks; no space has been allocated and the file is unchanged. Assert that the
 *       application does not proceed to write to the file as if the pre-allocation succeeded —
 *       subsequent writes may still fail with {@code ENOSPC} at an unpredictable point.
 *   <li>Database engines that use {@code fallocate} to pre-allocate WAL segment files must handle
 *       {@code ENOSPC} at segment creation time; assert that the database either falls back to
 *       writing without pre-allocation (accepting fragmentation and the risk of mid-write ENOSPC)
 *       or stops accepting new transactions until space is reclaimed.
 *   <li>Applications that pre-allocate large output files before writing must treat {@code ENOSPC}
 *       from {@code fallocate} as an early-warning disk-pressure signal; assert that the
 *       application triggers its disk-space alert path rather than silently falling back to writing
 *       without pre-allocation.
 *   <li>Assert that the application's error path on {@code ENOSPC} from {@code fallocate} produces
 *       the same user-visible message as {@code ENOSPC} from a write — both indicate disk full,
 *       regardless of the operation that surfaced it.
 * </ul>
 *
 * <p>In production, {@code ENOSPC} from {@code fallocate} is the canonical disk-full signal for
 * applications that use pre-allocation: it surfaces the space problem before any data has been
 * written, allowing the application to fail fast rather than discovering the disk is full after
 * partially writing a file. It occurs when the filesystem has fewer free blocks than the requested
 * allocation, including when the ext4 reserved-block pool prevents unprivileged processes from
 * using the last 5% of disk space.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fallocate(2)} allocates disk blocks for a file without writing any data. The key
 * benefit over writing zeroes is that the kernel reserves the blocks in the filesystem's allocation
 * bitmap before the application performs any I/O, guaranteeing that subsequent writes to the
 * pre-allocated region will succeed (barring hardware failure). When the filesystem has
 * insufficient free blocks, the kernel returns {@code ENOSPC} immediately without modifying the
 * file.
 *
 * <p>On ext4, the block allocator checks the number of free blocks against the requested allocation
 * and the filesystem's reserved-blocks threshold (controlled by {@code tune2fs -m}). If the
 * requested allocation would leave fewer than the reserved threshold, the kernel returns {@code
 * ENOSPC} even when there are technically some free blocks — those blocks are reserved for
 * root-owned processes. An unprivileged service process will see {@code ENOSPC} when the disk is at
 * 95% capacity even though the filesystem reports 5% free.
 *
 * <p>Java's NIO {@code FileChannel} does not directly expose {@code fallocate}. Applications that
 * use native libraries (SQLite via JDBC, RocksDB via JNI, custom file management code) may call
 * {@code fallocate} internally; their JNI bridge typically surfaces {@code ENOSPC} as an {@code
 * IOException} with the message "No space left on device". The JVM itself does not use {@code
 * fallocate} in its standard file I/O implementation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosAllocateEnospc(probability = 0.001)
 * class AllocateEnospcTest {
 *   @Test
 *   void walSegmentPreallocationEnospcStopsAcceptingTransactions() {
 *     // assert that ENOSPC on fallocate triggers disk-pressure alert and halts new transactions
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAllocateEdquot
 * @see ChaosWriteEnospc
 * @see ChaosFsyncEnospc
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosAllocateEnospc.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.ALLOCATE, errno = Errno.ENOSPC)
public @interface ChaosAllocateEnospc {

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
   * @ChaosAllocateEnospc(id = "primary",  probability = 0.001)
   * @ChaosAllocateEnospc(id = "replica",  probability = 0.01)
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
    ChaosAllocateEnospc[] value();
  }
}
