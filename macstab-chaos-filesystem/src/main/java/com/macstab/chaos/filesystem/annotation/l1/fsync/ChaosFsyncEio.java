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
 * Injects {@code EIO} into {@code fsync(2)}, causing the call to return {@code -1} with
 * {@code errno = EIO} as if the kernel could not flush all of the file's dirty pages to the
 * storage device because the device returned a hardware I/O error during the flush operation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code FSYNC}, errno = {@code EIO})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code fsync} call; when it fires the interposer returns {@code -1} with {@code errno = EIO}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code fsync} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EIO}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EIO} from {@code fsync} means the durability guarantee failed: some or all dirty
 *       pages that were written to the page cache since the last successful {@code fsync} are not
 *       guaranteed to have reached the storage device. Assert that the application does not consider
 *       the preceding writes durable and aborts any transaction or operation that depended on the
 *       fsync for its durability guarantee.
 *   <li>Write-ahead logging (WAL) implementations use {@code fsync} to make WAL records durable
 *       before acknowledging a transaction commit. An {@code EIO} on the WAL fsync means the WAL
 *       records may not be durable; the engine must abort all transactions that were waiting for
 *       this fsync to commit and must not send commit acknowledgements to clients.
 *   <li>The "write-to-temporary-then-rename" atomic update pattern requires both an {@code fsync}
 *       on the temporary file and an {@code fsync} on the parent directory; assert that an
 *       {@code EIO} on the temporary file's fsync causes the rename to be skipped, leaving the
 *       original target file intact.
 *   <li>Assert that the application marks the storage device as degraded after a configurable
 *       number of consecutive fsync failures and transitions to a read-only or error state to
 *       prevent accumulating unacknowledged writes.
 * </ul>
 *
 * <p>In production, {@code EIO} from {@code fsync} occurs when a disk sector that backs a dirty
 * page cannot be written (bad sector, reallocated sectors list full), when a write-through RAID
 * loses a member disk during the flush and the remaining disks cannot provide write parity, and
 * when a network filesystem's server-side write fails and the client-side fsync is rejected.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fsync(2)} flushes all dirty pages associated with the file descriptor's inode to the
 * storage device, including both data pages and metadata (inode, indirect blocks). It does not
 * return until the storage device acknowledges the write — or until an I/O error is detected.
 * When the device returns an error for any of the dirty pages being flushed, the kernel returns
 * {@code EIO} for the {@code fsync} call and clears the error flag so that a subsequent
 * {@code fsync} can succeed (if the device error was transient). The pages that failed to flush
 * remain dirty and will be retried on the next writeback.
 *
 * <p>A subtle but critical distinction: after {@code fsync} returns {@code EIO} on Linux, the
 * kernel clears the file's error flag. A subsequent successful {@code fsync} does not retroactively
 * make the data from before the failed fsync durable — it only makes the data written since the
 * last fsync durable. Applications that retry a failed fsync should be aware that the successful
 * retry confirms durability only for newly-written data.
 *
 * <p>Java's {@code FileDescriptor.sync()} and {@code FileChannel.force(boolean)} both call
 * {@code fsync(2)} under the hood. When the underlying call returns {@code EIO}, Java throws
 * a {@code SyncFailedException} (from {@code FileDescriptor.sync()}) or an {@code IOException}
 * (from {@code FileChannel.force()}). Application code must propagate this exception to the
 * transaction layer rather than catching and suppressing it.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosFsyncEio(probability = 0.001)
 * class FsyncEioTest {
 *   @Test
 *   void walFsyncFailureAbortsTransactionAndRefusesCommitAcknowledgement() {
 *     // assert that EIO on fsync aborts the WAL transaction rather than acknowledging commit
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosFdatasyncEio
 * @see ChaosWriteEio
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosFsyncEio.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.FSYNC, errno = Errno.EIO)
public @interface ChaosFsyncEio {

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
   * @ChaosFsyncEio(id = "primary",  probability = 0.001)
   * @ChaosFsyncEio(id = "replica",  probability = 0.01)
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
    ChaosFsyncEio[] value();
  }
}
