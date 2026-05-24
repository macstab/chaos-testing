/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.unlink;

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
 * Injects {@code EROFS} into {@code unlink(2)}, causing the call to return {@code -1} with {@code
 * errno = EROFS} as if the file's filesystem has been remounted read-only and the kernel cannot
 * remove the directory entry because no modifications are permitted on a read-only filesystem.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code UNLINK}, errno = {@code EROFS})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * unlink} call; when it fires the interposer returns {@code -1} with {@code errno = EROFS} without
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
 *   <li>On each intercepted {@code unlink} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EROFS}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EROFS} from {@code unlink} indicates the filesystem was remounted read-only; no
 *       directory modification operations (unlink, rename, mkdir) can succeed until the filesystem
 *       is repaired and remounted read-write. Assert that the application detects this condition as
 *       a filesystem-level error rather than a file-specific permission error and alerts operators
 *       with the filesystem state.
 *   <li>Temporary file cleanup paths that delete work files on completion must handle {@code EROFS}
 *       by logging the failure and reporting the filesystem state rather than retrying the deletion
 *       (which will continue to fail); assert that the application does not enter an infinite
 *       cleanup retry loop on a read-only filesystem.
 *   <li>Applications that implement "delete old logs on rotation" must handle {@code EROFS} on the
 *       deletion step; assert that the rotation aborts gracefully and the log writer continues in
 *       append mode (reads still succeed on a read-only filesystem).
 *   <li>Assert that the application's health check detects the read-only filesystem condition and
 *       returns a degraded status, distinguishing it from a normal "file not found" condition.
 * </ul>
 *
 * <p>In production, {@code EROFS} from {@code unlink} occurs when the kernel remounts the
 * filesystem read-only after detecting unrecoverable I/O errors during writeback, typically logged
 * in {@code dmesg} as "EXT4-fs error ... remounting filesystem read-only". All directory
 * modification operations will fail with {@code EROFS} until the filesystem is repaired.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code unlink(2)} removes a directory entry, which requires modifying the parent directory's
 * data blocks. On a read-only filesystem, the VFS layer rejects this modification before reaching
 * the filesystem-specific code, returning {@code EROFS} immediately. The check occurs regardless of
 * the process's permissions — even root cannot unlink a file on a read-only filesystem.
 *
 * <p>A read-only remount is typically triggered by the filesystem detecting a journal commit error
 * or a data write error, causing the ext4 or XFS filesystem code to call {@code remount_ro()} to
 * prevent further corruption. After a read-only remount, reads from pages already in the page cache
 * continue to succeed (the cache is not cleared), but reads that require fetching new pages may
 * also fail with {@code EIO} if the storage device itself is failing.
 *
 * <p>Java's {@code Files.delete(Path)} throws an {@code IOException} with the message "Read-only
 * file system" when the underlying {@code unlink} call returns {@code EROFS}. Application code that
 * catches this exception must distinguish it from a {@code NoSuchFileException} or a plain
 * permission-denied exception to apply the correct operator alert.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosUnlinkErofs(probability = 1.0)
 * class UnlinkErofsTest {
 *   @Test
 *   void readOnlyFilesystemOnUnlinkTriggersHealthCheckDegradedStatus() {
 *     // assert that EROFS on unlink causes the health check to return a degraded status
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosUnlinkEacces
 * @see ChaosWriteErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosUnlinkErofs.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.UNLINK, errno = Errno.EROFS)
public @interface ChaosUnlinkErofs {

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
   * @ChaosUnlinkErofs(id = "primary",  probability = 0.001)
   * @ChaosUnlinkErofs(id = "replica",  probability = 0.01)
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
    ChaosUnlinkErofs[] value();
  }
}
