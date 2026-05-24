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
 * Injects {@code EACCES} into {@code rename(2)} as observed from the source (old) path, causing the
 * call to return {@code -1} with {@code errno = EACCES} as if the calling process does not have
 * write permission on the directory containing the source file.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RENAME_FROM}, errno = {@code
 * EACCES}) tuple. The {@code RENAME_FROM} operation models the permission check on the source path
 * of {@code rename(2)}. A Bernoulli trial with probability {@link #probability} is run on each
 * intercepted {@code rename} call; when it fires the interposer returns {@code -1} with {@code
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
 *   <li>On each intercepted {@code rename} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EACCES}, simulating a permission failure on the source path.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EACCES} from a rename on the source path means the process cannot remove the source
 *       directory entry; the file is not moved or renamed. Assert that the application treats this
 *       as a permission error and does not assume the rename was partially performed (rename is
 *       atomic: it either fully succeeds or fails without any visible effect).
 *   <li>The "write-to-temporary-then-rename" atomic update pattern uses rename to make the new file
 *       visible atomically; an {@code EACCES} on the source path (the temporary file's directory)
 *       means the rename failed and the target file is unchanged. Assert that the application rolls
 *       back the write attempt and reports the permission failure.
 *   <li>Log rotation implementations that rename the current log file to an archive name must
 *       handle {@code EACCES} on the rename; assert that the rotation fails gracefully and the
 *       application continues to write to the original log file.
 *   <li>Assert that the application's error path on rename {@code EACCES} correctly identifies the
 *       affected path and the permission that was missing, enabling operators to diagnose the
 *       configuration issue.
 * </ul>
 *
 * <p>In production, {@code EACCES} from {@code rename} on the source path occurs when a security
 * policy (SELinux, AppArmor, filesystem ACL) prevents write access to the directory containing the
 * source file, when the sticky bit is set on the source directory and the process does not own the
 * source file or the directory, and when a container's security context is changed between the
 * temporary file creation and the rename.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code rename(2)} is an atomic directory operation: it removes the old name and adds the new
 * name in a single filesystem transaction, ensuring that observers either see the old name or the
 * new name, never an intermediate state. Implementing this atomically requires write permission on
 * both the source directory (to remove the old entry) and the destination directory (to add the new
 * entry). This annotation simulates a permission failure on the source directory.
 *
 * <p>When the rename fails, no visible change has occurred: the source file is still at its
 * original location and the destination is unchanged (or still absent if the destination did not
 * previously exist). Applications that use rename for atomic updates can safely retry or report
 * failure without concern for partial state.
 *
 * <p>Java's {@code Files.move(Path, Path, CopyOption...)} with {@code ATOMIC_MOVE} calls {@code
 * rename(2)} and throws an {@code IOException} with the message "Permission denied" when the
 * underlying call returns {@code EACCES}. Non-atomic move operations (without {@code ATOMIC_MOVE})
 * may fall back to a copy-then-delete sequence, which can leave partial state on failure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosRenameFromEacces(probability = 0.001)
 * class RenameFromEaccesTest {
 *   @Test
 *   void atomicFileUpdateRollsBackOnRenamePermissionFailure() {
 *     // assert that EACCES on rename leaves the target file unchanged and reports the error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRenameToEacces
 * @see ChaosUnlinkEacces
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosRenameFromEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.RENAME_FROM, errno = Errno.EACCES)
public @interface ChaosRenameFromEacces {

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
   * @ChaosRenameFromEacces(id = "primary",  probability = 0.001)
   * @ChaosRenameFromEacces(id = "replica",  probability = 0.01)
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
    ChaosRenameFromEacces[] value();
  }
}
