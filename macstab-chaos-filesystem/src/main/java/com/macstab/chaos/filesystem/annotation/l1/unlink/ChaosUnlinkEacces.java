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
 * Injects {@code EACCES} into {@code unlink(2)}, causing the call to return {@code -1} with {@code
 * errno = EACCES} as if the calling process does not have write permission on the directory
 * containing the file, or a path component requires search permission that the process does not
 * have.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code UNLINK}, errno = {@code
 * EACCES}) tuple. A Bernoulli trial with probability {@link #probability} is run on each
 * intercepted {@code unlink} call; when it fires the interposer returns {@code -1} with {@code
 * errno = EACCES} without performing any real kernel operation. No runtime operation-errno
 * validation is needed.
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
 *       errno = EACCES}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EACCES} from {@code unlink} means the process cannot remove the file due to a
 *       directory write permission failure; the file is not removed. Assert that the application
 *       handles this as a permission error and reports the failed file path, not as a silent
 *       success or as a disk error.
 *   <li>Temporary file cleanup paths that delete work files after completing an operation must
 *       handle {@code EACCES} if the temporary directory's permissions are changed by an external
 *       process; assert that the cleanup failure is logged and the temporary file is tracked for
 *       later cleanup rather than silently leaked.
 *   <li>Log rotation implementations that remove archived log files after the retention period must
 *       handle {@code EACCES} if the log directory's write permission is revoked; assert that the
 *       rotation reports the failure and continues with the remaining rotation steps rather than
 *       stopping at the first deletion failure.
 *   <li>Assert that the application's cleanup path on {@code EACCES} from unlink does not loop
 *       indefinitely retrying a permission-denied deletion, as the permission error will persist
 *       until an operator intervenes.
 * </ul>
 *
 * <p>In production, {@code EACCES} from {@code unlink} occurs when a security policy (SELinux
 * context, AppArmor profile, filesystem ACL) prevents the process from modifying the directory that
 * contains the file, even though the file itself is owned by the process, and when a container's
 * root filesystem is mounted read-only and the process attempts to delete a file.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code unlink(2)} removes a directory entry — it requires write permission on the parent
 * directory, not on the file itself. A process that owns a file but does not have write permission
 * on the file's parent directory will receive {@code EACCES} from {@code unlink}. This is a common
 * source of confusion: the process can read and write the file's data (it owns the file) but cannot
 * remove the file's directory entry (it lacks write on the directory).
 *
 * <p>The sticky bit ({@code chmod o+t /tmp}) modifies this rule: when set on a directory, only the
 * file's owner, the directory's owner, or the superuser can unlink files in the directory. Other
 * processes receive {@code EACCES} even if they have write permission on the directory. This
 * injection simulates the outcome of these permission scenarios without requiring actual permission
 * changes.
 *
 * <p>Java's {@code Files.delete(Path)} and {@code File.delete()} both call {@code unlink(2)} and
 * throw an {@code IOException} with the message "Permission denied" when the underlying call
 * returns {@code EACCES}. {@code Files.deleteIfExists(Path)} also throws rather than returning
 * {@code false} on {@code EACCES} — it only returns {@code false} on {@code ENOENT}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosUnlinkEacces(probability = 0.001)
 * class UnlinkEaccesTest {
 *   @Test
 *   void temporaryFileCleanupLogsPermissionFailureAndTracksForRetry() {
 *     // assert that EACCES on unlink logs the failure and tracks the file for later cleanup
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosUnlinkEnoent
 * @see ChaosUnlinkErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosUnlinkEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.UNLINK, errno = Errno.EACCES)
public @interface ChaosUnlinkEacces {

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
   * @ChaosUnlinkEacces(id = "primary",  probability = 0.001)
   * @ChaosUnlinkEacces(id = "replica",  probability = 0.01)
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
    ChaosUnlinkEacces[] value();
  }
}
