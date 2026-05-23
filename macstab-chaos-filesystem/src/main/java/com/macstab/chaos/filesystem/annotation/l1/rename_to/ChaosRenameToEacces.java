/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.rename_to;

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
 * Injects {@code EACCES} into {@code rename(2)} as observed from the destination (new) path,
 * causing the call to return {@code -1} with {@code errno = EACCES} as if the calling process
 * does not have write permission on the directory that would receive the new filename.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RENAME_TO}, errno =
 * {@code EACCES}) tuple. The {@code RENAME_TO} operation models the permission check on the
 * destination path of {@code rename(2)}: the calling process must have write permission on the
 * destination directory to add the new name. A Bernoulli trial with probability {@link #probability}
 * is run on each intercepted {@code rename} call; when it fires the interposer returns {@code -1}
 * with {@code errno = EACCES} without performing any real kernel operation.
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
 *   <li>On each intercepted {@code rename} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EACCES}, simulating a permission failure on the destination directory.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EACCES} from a rename on the destination path means the process cannot add the new
 *       name to the destination directory; the source file remains at its original location and
 *       the destination is unchanged. Assert that the application treats this as a permission error
 *       and correctly identifies the destination directory as the failing resource.
 *   <li>The "write temporary → rename to output directory" pattern fails when the destination
 *       directory has restrictive permissions; assert that the application preserves the source
 *       (temporary) file rather than deleting it on failure, enabling operator recovery.
 *   <li>Applications that promote work files from a staging area to a shared output directory
 *       owned by a different UID must handle {@code EACCES} on the rename to the output path;
 *       assert that the promotion failure is logged with the full destination path and the
 *       permissions of the output directory.
 *   <li>Distinguish from {@link ChaosRenameFromEacces}: a {@code RENAME_FROM} failure means
 *       the process cannot remove the old name; a {@code RENAME_TO} failure means it cannot add
 *       the new name. Applications should log which path caused the denial.
 * </ul>
 *
 * <p>In production, {@code EACCES} from {@code rename} on the destination path occurs when the
 * process runs as a service user that lacks write permission on the target directory (common when
 * an administrator restricts write access to an output directory after deployment), when SELinux
 * or AppArmor policy prevents the process from writing to the destination context, and when the
 * sticky bit on the destination's parent directory prevents overwriting a file owned by another
 * user.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code rename(2)} requires write and execute permissions on both the source and destination
 * directories. The POSIX permission model checks them independently: the source-directory check
 * verifies the process can remove an entry (DAC write permission on source dir), and the
 * destination-directory check verifies the process can add an entry (DAC write permission on
 * destination dir). This annotation injects {@code EACCES} simulating a failure at the
 * destination-directory step specifically.
 *
 * <p>The sticky bit adds a further restriction: even with write permission on the directory,
 * a process cannot overwrite an existing destination entry if the destination file is owned by
 * a different user and the process does not have {@code CAP_FOWNER}. The kernel checks
 * stickiness after DAC — an {@code EACCES} from stickiness is indistinguishable to userspace
 * from an {@code EACCES} from missing write permission.
 *
 * <p>Java's {@code Files.move(Path, Path, ATOMIC_MOVE)} wraps {@code rename(2)} and throws
 * {@code IOException("Permission denied")} for {@code EACCES}. The {@code IOException} message
 * does not distinguish source-path from destination-path failures; applications that must route
 * the error to the right operator alert need to inspect both paths' permissions after the failure.
 *
 * <p>Compared with {@link ChaosRenameToErofs}: {@code EACCES} is a policy failure (potentially
 * resolvable by changing directory permissions or running as a different user); {@code EROFS}
 * is a storage-system failure (requires filesystem repair). Use {@code EACCES} to test the
 * permission-denied path; use {@code EROFS} to test the storage-degradation path.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosRenameToEacces(probability = 0.001)
 * class RenameToEaccesTest {
 *   @Test
 *   void promotionFailurePreservesSourceFileAndLogsDestinationPath() {
 *     // assert that EACCES on rename to output dir leaves source at staging path
 *     // and that the logged error identifies the output directory
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRenameFromEacces
 * @see ChaosRenameToErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosRenameToEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.RENAME_TO, errno = Errno.EACCES)
public @interface ChaosRenameToEacces {

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
   * @ChaosRenameToEacces(id = "primary",  probability = 0.001)
   * @ChaosRenameToEacces(id = "replica",  probability = 0.01)
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
    ChaosRenameToEacces[] value();
  }
}
