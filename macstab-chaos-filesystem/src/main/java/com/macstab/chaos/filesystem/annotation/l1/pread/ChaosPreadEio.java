/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.pread;

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
 * Injects {@code EIO} into {@code pread(2)}, causing the call to return {@code -1} with {@code
 * errno = EIO} as if the storage device returned a hardware I/O error while reading the data blocks
 * at the requested file offset — indicating a bad sector, storage controller failure, or network
 * filesystem backend read error.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code PREAD}, errno = {@code EIO})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * pread} call; when it fires the interposer returns {@code -1} with {@code errno = EIO} without
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
 *   <li>On each intercepted {@code pread} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EIO}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EIO} from {@code pread} means the data at the requested file offset could not be
 *       read from storage; the contents of the caller's buffer are undefined. Assert that the
 *       application does not use the buffer contents after a failed {@code pread} and reports the
 *       I/O error to the caller rather than returning undefined data.
 *   <li>{@code pread(2)} is commonly used by database engines for random-access page reads. An
 *       {@code EIO} on a page read means the page cannot be loaded; the engine must propagate the
 *       error to the query that required the page and must not cache the undefined buffer as if the
 *       page were successfully read. Assert that the query returns an error rather than incorrect
 *       results.
 *   <li>Applications that implement read-retry logic for transient I/O errors must distinguish a
 *       permanently-failing bad sector ({@code EIO} on every retry) from a transient storage
 *       timeout. Assert that the retry budget is not exhausted on permanently-failing sectors and
 *       that the application escalates to a storage-level health alert.
 *   <li>Assert that {@code EIO} on a B-tree index page read does not cause the engine to traverse
 *       the tree with a partially-initialised page buffer, which could lead to incorrect results or
 *       a null pointer dereference when dereferencing the page's child pointers.
 * </ul>
 *
 * <p>In production, {@code EIO} from {@code pread} occurs when a disk sector has been marked
 * reallocated and the spare sector pool is exhausted (the SMART reallocated sectors count reaches
 * the drive's limit), when an NFS server returns a read error and the client propagates it as
 * {@code EIO}, and when a RAID array loses enough members that a stripe cannot be reconstructed and
 * the kernel returns {@code EIO} to the reader.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code pread(2)} is the positional variant of {@code read(2)}: it reads from a caller-supplied
 * offset rather than the file's current position, and does not modify the file position. This makes
 * it safe for concurrent use by multiple threads reading from different parts of the same file
 * without locking around the {@code lseek}/{@code read} pair. Database storage engines use {@code
 * pread} to issue concurrent page reads from multiple worker threads, each specifying the page's
 * exact file offset.
 *
 * <p>When the block device reports a medium error for the physical sectors that back the requested
 * file offset, the kernel's block I/O layer propagates the error to the filesystem layer, which
 * propagates it to {@code pread}. Unlike buffered {@code read} where deferred writeback can cause
 * delayed error delivery, {@code pread} errors from the page cache miss path are synchronous: the
 * error is returned to the caller of the {@code pread} that triggered the page-in, not to a later
 * call.
 *
 * <p>Java's {@code FileChannel.read(ByteBuffer, long)} maps directly to {@code pread(2)} on Linux.
 * When the underlying call returns {@code EIO}, the JVM throws an {@code IOException} with the
 * message "Input/output error". Application code that catches this exception and retries
 * indefinitely on a permanently-failing sector will loop forever; it should apply a retry budget
 * and escalate to a storage-level alert when the budget is exhausted.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosPreadEio(probability = 0.001)
 * class PreadEioTest {
 *   @Test
 *   void pageReadFailureReturnsErrorNotCorruptedData() {
 *     // assert that EIO on pread returns an error to the query rather than undefined page contents
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReadEio
 * @see ChaosPreadCorrupt
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosPreadEio.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.PREAD, errno = Errno.EIO)
public @interface ChaosPreadEio {

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
   * @ChaosPreadEio(id = "primary",  probability = 0.001)
   * @ChaosPreadEio(id = "replica",  probability = 0.01)
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
    ChaosPreadEio[] value();
  }
}
