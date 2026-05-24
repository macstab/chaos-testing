/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.rename_from;

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
 * Injects {@code EROFS} into {@code rename(2)} as observed from the source (old) path, causing the
 * call to return {@code -1} with {@code errno = EROFS} as if the filesystem containing the source
 * directory entry has been remounted read-only and the kernel cannot remove that entry.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RENAME_FROM}, errno = {@code
 * EROFS}) tuple. The {@code RENAME_FROM} operation models the source-path permission check of
 * {@code rename(2)}: the VFS must be able to remove the old directory entry, which requires write
 * access to the source directory — impossible on a read-only filesystem. A Bernoulli trial with
 * probability {@link #probability} is run on each intercepted {@code rename} call; when it fires
 * the interposer returns {@code -1} with {@code errno = EROFS} without performing any real kernel
 * operation.
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
 *   <li>On each intercepted {@code rename} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EROFS}, simulating a read-only filesystem blocking the source-path unlink.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EROFS} from {@code rename} on the source path indicates the filesystem was remounted
 *       read-only; no directory modification operations can succeed until the filesystem is
 *       repaired and remounted read-write. Assert that the application detects this as a
 *       filesystem-level error rather than a file-specific permission error.
 *   <li>The "write-to-temporary-then-rename" atomic update pattern relies on rename to make the new
 *       file visible; an {@code EROFS} on the source path means the entire atomic update failed and
 *       the target is unchanged. Assert that the application discards the temporary file without
 *       re-attempting (which would also fail) and alerts operators.
 *   <li>Log rotation that renames the current log file to an archive name must handle {@code
 *       EROFS}; assert that the rotation aborts and the application continues writing to the
 *       original log file rather than crashing or losing subsequent log lines.
 *   <li>Assert that the application's health check detects the read-only condition and returns a
 *       degraded status that distinguishes it from a permission-denied failure.
 * </ul>
 *
 * <p>In production, {@code EROFS} from {@code rename} on the source path occurs when the kernel
 * remounts the filesystem read-only after detecting unrecoverable write errors during journalling,
 * typically visible in {@code dmesg} as "EXT4-fs error ... remounting filesystem read-only". Once
 * remounted, all directory-modification operations — rename, unlink, mkdir — fail with {@code
 * EROFS} until the filesystem is repaired with {@code fsck} and explicitly remounted read-write.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code rename(2)} is a two-step directory operation at the VFS level: first the old name is
 * removed from the source directory's entry list (requiring write permission on the source
 * directory), then the new name is added to the destination directory's entry list (requiring write
 * permission on the destination directory). Both steps must be possible for {@code rename} to
 * succeed. On a read-only filesystem the VFS layer rejects the source-directory modification before
 * even attempting the destination, returning {@code EROFS} immediately.
 *
 * <p>The read-only remount propagates across the entire filesystem: every file and directory on the
 * same block device (or overlay layer) becomes read-only simultaneously. This means that a single
 * {@code rename} returning {@code EROFS} is diagnostic of the entire storage subsystem, not just
 * the one path involved in the rename. Applications that issue {@code rename} and receive {@code
 * EROFS} must assume that subsequent writes, fsyncs, unlinks, and renames on the same filesystem
 * will all fail with {@code EROFS}.
 *
 * <p>Java's {@code Files.move(Path, Path, CopyOption...)} with {@code ATOMIC_MOVE} calls {@code
 * rename(2)} and throws an {@code IOException} with the message "Read-only file system" when it
 * returns {@code EROFS}. Unlike {@code EACCES} (which might be resolvable by changing permissions),
 * {@code EROFS} is a hardware-level condition — no change to file permissions or security policies
 * will allow the rename until the filesystem is remounted read-write.
 *
 * <p>Compared with {@link ChaosRenameFromEacces}: {@code EROFS} simulates the entire filesystem
 * being read-only (no writes of any kind succeed); {@code EACCES} simulates a targeted permission
 * denial on a specific directory entry (other directories may still be writable). Use {@code EROFS}
 * to test the global read-only degradation path; use {@code EACCES} to test per-path permission
 * handling.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosRenameFromErofs(probability = 1.0)
 * class RenameFromErofsTest {
 *   @Test
 *   void atomicUpdateAbortsAndAlertsOnReadOnlyFilesystem() {
 *     // assert that EROFS on rename causes health check to return degraded
 *     // and that no partial state is left at the target path
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRenameFromEacces
 * @see ChaosRenameToErofs
 * @see ChaosWriteErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosRenameFromErofs.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.RENAME_FROM, errno = Errno.EROFS)
public @interface ChaosRenameFromErofs {

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
   * @ChaosRenameFromErofs(id = "primary",  probability = 0.001)
   * @ChaosRenameFromErofs(id = "replica",  probability = 0.01)
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
    ChaosRenameFromErofs[] value();
  }
}
