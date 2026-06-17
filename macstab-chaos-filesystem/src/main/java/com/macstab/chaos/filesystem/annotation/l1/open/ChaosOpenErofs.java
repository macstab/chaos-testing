/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.open;

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
 * Injects {@code EROFS} into {@code open(2)}, causing the call to return {@code -1} with {@code
 * errno = EROFS} as if the file resides on a read-only filesystem and the caller attempted to open
 * it for writing, create it, or truncate it.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code OPEN}, errno = {@code EROFS})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * open} call; when it fires the interposer returns {@code -1} with {@code errno = EROFS} without
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
 *   <li>On each intercepted {@code open} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EROFS}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EROFS} from {@code open} indicates that a write-intent open on a read-only
 *       filesystem was rejected; the filesystem itself is the source of the error, not the file's
 *       permissions. Assert that the application reports a "filesystem is read-only" error rather
 *       than a permissions error — the remediation (remounting the filesystem read-write or
 *       redirecting writes to another volume) is different from a permissions fix.
 *   <li>Applications that write logs, configuration, or data to the same filesystem as their binary
 *       must handle {@code EROFS} when the filesystem is remounted read-only after a disk error;
 *       assert that the application falls back to a writable location or fails with a clear
 *       message.
 *   <li>PID files and lock files written by daemon processes must handle {@code EROFS} at startup —
 *       a daemon that cannot write its PID file should fail with a clear error rather than running
 *       without locking.
 *   <li>Assert that the application correctly differentiates {@code EROFS} from {@code EACCES}:
 *       both prevent writing, but {@code EROFS} indicates a filesystem-level constraint that cannot
 *       be resolved by changing file permissions.
 * </ul>
 *
 * <p>In production, {@code EROFS} from {@code open} occurs when a disk develops read errors that
 * cause the kernel to remount the filesystem read-only to prevent further corruption (visible in
 * {@code dmesg} as "EXT4-fs error ... remounting filesystem read-only"), when a Kubernetes
 * read-only root filesystem ({@code readOnlyRootFilesystem: true} in the security context) is in
 * effect, and when a container image layer is mounted read-only and an application writes to a path
 * that should have been an overlay writable layer but is not.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel returns {@code EROFS} when the filesystem's superblock has the read-only flag set
 * and the caller requests write access (any combination of {@code O_WRONLY}, {@code O_RDWR}, {@code
 * O_CREAT}, {@code O_TRUNC}, or {@code O_APPEND}). The check is performed before any per-file
 * permission check; even root cannot write to a read-only filesystem without first remounting it
 * read-write using {@code mount -o remount,rw}.
 *
 * <p>The ext4 filesystem remounts itself read-only automatically when it encounters an
 * unrecoverable error during journal recovery, inode table access, or block group descriptor write.
 * This protects the filesystem from further corruption but causes all subsequent write-intent opens
 * to return {@code EROFS}. The remount is reported in the kernel log and can be detected by
 * monitoring {@code /proc/mounts} for the {@code ro} mount option appearing on a previously {@code
 * rw} filesystem.
 *
 * <p>Java maps {@code EROFS} from {@code open} to an {@code IOException} with the message
 * "Read-only file system". Unlike {@code EACCES} which maps to {@code FileNotFoundException} in
 * some Java APIs, {@code EROFS} consistently maps to {@code IOException}. Application code that
 * catches both {@code FileNotFoundException} and {@code IOException} must check both to handle all
 * disk-access failure modes.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosOpenErofs(probability = 1.0)
 * class OpenErofsTest {
 *   @Test
 *   void readOnlyFilesystemRemountCausesClearStartupFailure() {
 *     // assert that the daemon reports "filesystem is read-only" and exits with a non-zero code
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosOpenEacces
 * @see ChaosWriteErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosOpenErofs.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.OPEN, errno = Errno.EROFS)
public @interface ChaosOpenErofs {

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
   * @ChaosOpenErofs(id = "primary",  probability = 0.001)
   * @ChaosOpenErofs(id = "replica",  probability = 0.01)
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
    ChaosOpenErofs[] value();
  }
}
