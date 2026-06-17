/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.truncate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteErofs;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code EROFS} into {@code truncate(2)}, causing the call to return {@code -1} with {@code
 * errno = EROFS} as if the file's filesystem has been remounted read-only and the kernel cannot
 * modify the file's size because no modifications are permitted on a read-only filesystem.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code TRUNCATE}, errno = {@code
 * EROFS}) tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code truncate} call; when it fires the interposer returns {@code -1} with {@code errno = EROFS}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code truncate} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EROFS}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EROFS} from {@code truncate} indicates that the filesystem was remounted read-only,
 *       typically after detecting unrecoverable I/O errors; all subsequent write operations on the
 *       filesystem will fail with the same error. Assert that the application detects the read-only
 *       condition and transitions to a degraded state that prevents new write operations from
 *       accumulating without persistence.
 *   <li>Applications that use {@code truncate} as part of a write preparation sequence (size the
 *       file, then write) must handle {@code EROFS} by aborting the entire sequence and reporting
 *       the filesystem condition to the operator; assert that no partial write sequence is left in
 *       place when the truncate fails.
 *   <li>WAL pre-allocation patterns that use {@code truncate} to size the WAL file must handle
 *       {@code EROFS}; assert that the database fails to start or transitions to read-only mode
 *       rather than attempting to write to the unmodified WAL file.
 *   <li>Assert that the application's health check detects the read-only filesystem condition and
 *       returns a degraded status, enabling load balancers to redirect write traffic away from the
 *       instance.
 * </ul>
 *
 * <p>In production, {@code EROFS} from {@code truncate} occurs when the kernel remounts a
 * filesystem read-only after detecting unrecoverable I/O errors during writeback. The event is
 * logged in {@code dmesg} with a message like "EXT4-fs error (device sda1): ... remounting
 * filesystem read-only". All modification operations on the filesystem will fail with {@code EROFS}
 * until the filesystem is unmounted, repaired, and remounted read-write.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code truncate(2)} modifies the file's inode (to update the size field) and may allocate or
 * free data blocks. Both operations require the filesystem to be mounted read-write. When the
 * filesystem is mounted read-only, the VFS layer rejects the modification before reaching the
 * filesystem-specific code, returning {@code EROFS} immediately. This check occurs before any file
 * permission checks, so {@code EROFS} takes precedence over {@code EACCES}: a process without write
 * permission on the file still receives {@code EROFS} rather than {@code EACCES} when the
 * filesystem is read-only.
 *
 * <p>Java's {@code FileChannel.truncate(long)} calls {@code ftruncate(2)} via an open file
 * descriptor and throws an {@code IOException} with the message "Read-only file system" when the
 * underlying call returns {@code EROFS}. Application code that catches {@code IOException} and
 * inspects the message should be aware that the message text varies across platforms.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosTruncateErofs(probability = 1.0)
 * class TruncateErofsTest {
 *   @Test
 *   void readOnlyFilesystemOnTruncateTransitionsDatabaseToReadOnlyMode() {
 *     // assert that EROFS on truncate triggers the database's read-only fallback mode
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosTruncateEacces
 * @see ChaosWriteErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosTruncateErofs.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.TRUNCATE, errno = Errno.EROFS)
public @interface ChaosTruncateErofs {

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
   * @ChaosTruncateErofs(id = "primary",  probability = 0.001)
   * @ChaosTruncateErofs(id = "replica",  probability = 0.01)
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
    ChaosTruncateErofs[] value();
  }
}
