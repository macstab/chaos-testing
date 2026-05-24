/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.pwrite;

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
 * Injects {@code EIO} into {@code pwrite(2)}, causing the call to return {@code -1} with {@code
 * errno = EIO} as if the storage device returned a hardware I/O error while writing the data blocks
 * at the requested file offset — indicating a bad sector, storage controller failure, or network
 * filesystem backend write error.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code PWRITE}, errno = {@code EIO})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * pwrite} call; when it fires the interposer returns {@code -1} with {@code errno = EIO} without
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
 *   <li>On each intercepted {@code pwrite} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EIO}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EIO} from {@code pwrite} means data at the requested file offset was not written to
 *       storage; the file's contents at that offset are undefined. Assert that the application does
 *       not proceed as if the write succeeded and aborts the current operation rather than
 *       continuing with a partially-written file.
 *   <li>Database engines that use {@code pwrite} for B-tree page writes must treat {@code EIO} as a
 *       fatal page write failure; the engine must abort all in-progress transactions that touched
 *       the affected page and must not mark them as committed. Assert that the WAL records for the
 *       aborted transactions are not replayed on recovery.
 *   <li>Applications that implement write-retry logic for transient I/O errors must distinguish a
 *       permanently-failing bad sector from a transient controller hiccup. Assert that the retry
 *       budget is applied and that the application escalates to a storage-level health alert when
 *       the budget is exhausted.
 *   <li>Assert that {@code EIO} on a write at a critical file offset (header, directory block,
 *       bitmap block) triggers the application's corruption-detection path and causes it to
 *       transition to a read-only or error state rather than continuing to accept new writes to
 *       adjacent regions.
 * </ul>
 *
 * <p>In production, {@code EIO} from {@code pwrite} occurs when a disk sector is reallocated and
 * the spare sector pool is exhausted, when a RAID degraded-mode write fails on the remaining member
 * disk, and when a network filesystem's storage backend encounters a write error and the
 * client-side kernel propagates it as {@code EIO} synchronously (because {@code pwrite} on NFS uses
 * the synchronous I/O path when {@code O_DIRECT} is set or when the NFS mount uses {@code sync}
 * option).
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code pwrite(2)} is the positional variant of {@code write(2)}: it writes to a
 * caller-supplied offset without modifying the file's current position, making it thread-safe for
 * concurrent page writes. Database storage engines use it exclusively for page I/O to allow
 * multiple writer threads to write to different pages concurrently. For direct I/O ({@code
 * O_DIRECT}), the write goes directly to the block device and {@code EIO} is returned synchronously
 * when the block device fails. For buffered I/O, the kernel accepts data into the page cache and
 * may deliver the error asynchronously during writeback.
 *
 * <p>This injection simulates the synchronous error path regardless of the file's open flags, which
 * is the more demanding case for application error handling: the application knows immediately that
 * the write failed and must handle the error before issuing further writes. The deferred writeback
 * error path (where {@code EIO} is delivered on the next write or {@code fsync}) is modelled by
 * {@link ChaosWriteEio} and {@link ChaosFsyncEio}.
 *
 * <p>Java's {@code FileChannel.write(ByteBuffer, long)} maps to {@code pwrite(2)} on Linux. When
 * the underlying call returns {@code EIO}, the JVM throws an {@code IOException} with the message
 * "Input/output error". Application code must catch this exception and treat it as a fatal write
 * error, not a transient condition to be retried without limit.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosPwriteEio(probability = 0.001)
 * class PwriteEioTest {
 *   @Test
 *   void pageWriteFailureCausesTransactionAbortNotCommit() {
 *     // assert that EIO on pwrite aborts the transaction rather than committing partial page data
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosWriteEio
 * @see ChaosFsyncEio
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosPwriteEio.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.PWRITE, errno = Errno.EIO)
public @interface ChaosPwriteEio {

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
   * @ChaosPwriteEio(id = "primary",  probability = 0.001)
   * @ChaosPwriteEio(id = "replica",  probability = 0.01)
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
    ChaosPwriteEio[] value();
  }
}
