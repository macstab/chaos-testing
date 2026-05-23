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
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code EIO} into {@code fdatasync(2)}, causing the call to return {@code -1} with
 * {@code errno = EIO} as if the kernel could not flush the file's dirty data pages to the storage
 * device because the device returned a hardware I/O error during the data flush operation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code FDATASYNC}, errno = {@code EIO})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code fdatasync} call; when it fires the interposer returns {@code -1} with {@code errno = EIO}
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
 *   <li>On each intercepted {@code fdatasync} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EIO}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EIO} from {@code fdatasync} means the data durability guarantee failed: the dirty
 *       data pages written since the last successful fdatasync are not guaranteed to have reached
 *       the storage device. Assert that the application does not consider the preceding writes
 *       durable and aborts any transaction or operation that depended on the fdatasync.
 *   <li>Database engines that use {@code fdatasync} rather than {@code fsync} to avoid flushing
 *       metadata (relying on the size and timestamp being recoverable from the data) must treat
 *       {@code EIO} from {@code fdatasync} as a fatal data flush failure; assert that the engine
 *       aborts all pending transactions and refuses new writes until the storage error is resolved.
 *   <li>WAL implementations that use {@code fdatasync} for WAL record durability (preferring it
 *       over {@code fsync} for performance when the WAL file has a pre-allocated size) must handle
 *       {@code EIO} by aborting all transactions waiting for this fdatasync. Assert that the WAL
 *       writer does not send commit acknowledgements when the fdatasync returned {@code EIO}.
 *   <li>Assert that the application's error path on fdatasync failure correctly distinguishes a
 *       data flush failure ({@code EIO}) from a disk-full condition ({@code ENOSPC}) and applies
 *       the appropriate remediation.
 * </ul>
 *
 * <p>In production, {@code EIO} from {@code fdatasync} occurs under the same conditions as
 * {@code EIO} from {@code fsync} — bad sectors, storage controller failures, and NFS backend
 * errors — but only for the data pages, not the metadata. On a filesystem that is otherwise
 * healthy, {@code fdatasync} {@code EIO} indicates that a specific data region has a persistent
 * write error.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fdatasync(2)} is like {@code fsync(2)} but only flushes the file's data pages and
 * the minimum metadata needed to access the data (specifically the file size if it changed);
 * it does not flush metadata that is not strictly necessary for data access (modification time,
 * access time). This makes {@code fdatasync} faster than {@code fsync} on journalled filesystems
 * because the journal does not need to be committed for metadata-only changes.
 *
 * <p>Database engines that pre-allocate WAL file space using {@code fallocate} at startup use
 * {@code fdatasync} rather than {@code fsync} for WAL record commits because the file size does
 * not change on each commit (only data is written within the pre-allocated region). {@code fsync}
 * would also flush the journal commit block for the metadata, adding unnecessary overhead.
 * {@code fdatasync} provides the same data durability guarantee at lower cost.
 *
 * <p>Java's {@code FileChannel.force(false)} calls {@code fdatasync(2)} on Linux; {@code force(true)}
 * calls {@code fsync(2)}. When {@code force(false)} fails with {@code EIO}, Java throws an
 * {@code IOException} with the message "Input/output error". Application code must propagate
 * this exception to the transaction layer rather than catching and suppressing it.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosFdatasyncEio(probability = 0.001)
 * class FdatasyncEioTest {
 *   @Test
 *   void walFdatasyncFailureAbortsTransactionAndRefusesCommitAcknowledgement() {
 *     // assert that EIO on fdatasync aborts the WAL transaction rather than acknowledging commit
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosFsyncEio
 * @see ChaosWriteEio
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosFdatasyncEio.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.FDATASYNC, errno = Errno.EIO)
public @interface ChaosFdatasyncEio {

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
   * @ChaosFdatasyncEio(id = "primary",  probability = 0.001)
   * @ChaosFdatasyncEio(id = "replica",  probability = 0.01)
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
    ChaosFdatasyncEio[] value();
  }
}
