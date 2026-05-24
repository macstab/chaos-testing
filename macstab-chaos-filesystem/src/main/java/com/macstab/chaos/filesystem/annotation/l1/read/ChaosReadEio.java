/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.read;

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
 * Injects {@code EIO} into {@code read(2)}, causing the call to return {@code -1} with {@code errno
 * = EIO} as if the storage device returned a hardware I/O error while reading the requested file
 * blocks — indicating a bad sector, storage controller failure, or network filesystem backend
 * error.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code READ}, errno = {@code EIO})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * read} call; when it fires the interposer returns {@code -1} with {@code errno = EIO} without
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
 *   <li>On each intercepted {@code read} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EIO}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EIO} from {@code read} indicates unrecoverable hardware-level failure; the data at
 *       the failed block offset cannot be read. Assert that the application does not retry
 *       indefinitely on {@code EIO} — unlike transient errors, a bad sector does not self-heal.
 *   <li>Database engines that read data pages from storage files must handle {@code EIO} by marking
 *       the affected page range as corrupt and alerting the operator; assert that the engine does
 *       not proceed with partial or zero data silently.
 *   <li>Applications that read from WAL (write-ahead log) files for crash recovery must handle
 *       {@code EIO} in the WAL read path; assert that the recovery process fails with a clear "WAL
 *       read error" rather than treating a partial WAL as complete.
 *   <li>Assert that the application emits a storage hardware error alert on {@code EIO} from {@code
 *       read}, enabling operators to check {@code dmesg} and SMART data for the affected device.
 * </ul>
 *
 * <p>In production, {@code EIO} from {@code read} occurs when a hard disk sector becomes unreadable
 * (pending sector reallocations visible in SMART data), when an SSD's flash memory cell fails to
 * charge reliably (uncorrectable bit error rate exceeded), and when a network filesystem (NFS,
 * Ceph) returns an error from the storage backend and the kernel propagates it to the application
 * as {@code EIO}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel propagates {@code EIO} from storage to the application through several layers: the
 * block device driver returns an error for the failed I/O request; the filesystem layer (ext4, XFS)
 * receives the block-layer error and marks the associated page buffer as error-pending; when the
 * application calls {@code read}, the VFS layer checks the page buffer's error flag and returns
 * {@code EIO} if it is set. The error flag persists until the page is evicted from the page cache
 * or explicitly cleared.
 *
 * <p>The kernel also marks the filesystem's block group containing the bad sector as having an
 * error, which is recorded in the superblock and visible in {@code dumpe2fs} output. If multiple
 * read errors occur, the filesystem may remount itself read-only to prevent further corruption —
 * causing subsequent reads to return either data (for pages still in cache) or {@code EROFS} (for
 * new reads on a read-only mounted filesystem).
 *
 * <p>Java maps {@code EIO} from {@code read} to an {@code IOException} with the message
 * "Input/output error". Applications that catch {@code IOException} generically may not distinguish
 * hardware I/O errors from other IO failures; critical data paths should check for "Input/output
 * error" in the message or use OS-specific facilities to query the block device health.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosReadEio(probability = 0.001)
 * class ReadEioTest {
 *   @Test
 *   void storageErrorOnDataFileReadTriggersAlertAndSafeShutdown() {
 *     // assert that EIO on a data file read causes an alert and controlled shutdown
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReadCorrupt
 * @see ChaosWriteEio
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosReadEio.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.READ, errno = Errno.EIO)
public @interface ChaosReadEio {

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
   * @ChaosReadEio(id = "primary",  probability = 0.001)
   * @ChaosReadEio(id = "replica",  probability = 0.01)
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
    ChaosReadEio[] value();
  }
}
