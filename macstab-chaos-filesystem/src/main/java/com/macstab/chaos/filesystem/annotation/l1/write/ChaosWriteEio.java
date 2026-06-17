/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.write;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.annotation.l1.fsync.ChaosFsyncEio;
import com.macstab.chaos.filesystem.annotation.l1.read.ChaosReadEio;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code EIO} into {@code write(2)}, causing the call to return {@code -1} with {@code
 * errno = EIO} as if the storage device returned a hardware I/O error while writing the data blocks
 * — indicating a bad sector, storage controller failure, or network filesystem backend write error.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code WRITE}, errno = {@code EIO})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * write} call; when it fires the interposer returns {@code -1} with {@code errno = EIO} without
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
 *   <li>On each intercepted {@code write} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EIO}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EIO} from {@code write} means data was not written to storage; any data in the
 *       kernel's write buffer for the affected block range is lost. Assert that the application
 *       does not proceed as if the write succeeded — it must abort the operation and report the
 *       hardware error rather than continuing with a partially-written file.
 *   <li>Write-ahead logging (WAL) implementations must handle {@code EIO} on WAL writes by aborting
 *       all in-progress transactions and entering a recovery mode; assert that the WAL writer does
 *       not mark transactions as committed when the WAL write fails.
 *   <li>Applications that buffer writes in memory and flush periodically must handle {@code EIO} on
 *       the flush path; assert that un-flushed data is preserved in memory for retry (if possible)
 *       or that the data loss is reported to the caller.
 *   <li>Assert that the application marks the storage device as degraded and switches to a replica
 *       or backup storage path when it encounters {@code EIO} on write, enabling continued
 *       operation while the failing device is replaced.
 * </ul>
 *
 * <p>In production, {@code EIO} from {@code write} occurs when a disk sector cannot be written
 * (reallocated sectors list is full and no spare sectors are available), when a RAID degraded mode
 * write fails on the remaining member disk, and when a network filesystem's storage backend
 * encounters a write error and the client-side kernel propagates it as {@code EIO}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>For buffered writes, the kernel accepts the data into the page cache and returns immediately
 * without waiting for the data to reach the storage device. The actual storage write happens
 * asynchronously via the kernel's writeback mechanism. When the writeback encounters an I/O error,
 * the kernel marks the page as error-pending and sets the file's error flag. The error is delivered
 * to the application on the next {@code write} call that triggers an error check, or on {@code
 * fsync} if the application uses synchronous write patterns.
 *
 * <p>For direct I/O ({@code O_DIRECT}), the write bypasses the page cache and goes directly to the
 * block device. If the block device returns an error, {@code write} returns {@code EIO}
 * synchronously. This injection simulates the direct I/O failure path regardless of whether the
 * actual file was opened with {@code O_DIRECT}.
 *
 * <p>Java maps {@code EIO} from {@code write} to an {@code IOException} with the message
 * "Input/output error". The same message is used for read-side {@code EIO}; application code should
 * log whether the error occurred on a read or write operation, as the data loss implications
 * differ: a read-side EIO means data cannot be retrieved, while a write-side EIO means data was not
 * persisted.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosWriteEio(probability = 0.001)
 * class WriteEioTest {
 *   @Test
 *   void walWriteFailureCausesTransactionAbortNotCommit() {
 *     // assert that a WAL write EIO aborts the transaction rather than committing partial data
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReadEio
 * @see ChaosFsyncEio
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosWriteEio.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.WRITE, errno = Errno.EIO)
public @interface ChaosWriteEio {

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
   * @ChaosWriteEio(id = "primary",  probability = 0.001)
   * @ChaosWriteEio(id = "replica",  probability = 0.01)
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
    ChaosWriteEio[] value();
  }
}
