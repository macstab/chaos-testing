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
 * Injects {@code ENOENT} into {@code rename(2)} as observed from the source (old) path, causing the
 * call to return {@code -1} with {@code errno = ENOENT} as if the source file does not exist or a
 * component of the source path prefix is a directory that does not exist.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RENAME_FROM}, errno = {@code
 * ENOENT}) tuple. A Bernoulli trial with probability {@link #probability} is run on each
 * intercepted {@code rename} call; when it fires the interposer returns {@code -1} with {@code
 * errno = ENOENT} without performing any real kernel operation. No runtime operation-errno
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
 *       errno = ENOENT}, simulating an absent source path.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOENT} from a rename on the source path means the temporary or source file does not
 *       exist at the expected location; the target path is unchanged. Assert that the application
 *       treats this as a TOCTOU race (the source file was deleted by another process between
 *       creation and rename) and reports an appropriate error rather than proceeding as if the
 *       rename succeeded.
 *   <li>The "write-to-temporary-then-rename" atomic update pattern relies on the temporary file
 *       existing at the expected path when rename is called; {@code ENOENT} means the temporary
 *       file was removed (by a concurrent cleanup process) before the rename completed. Assert that
 *       the application aborts the write sequence and does not leave the target file in an
 *       inconsistent state.
 *   <li>Log rotation implementations that rename the current log file before opening a new one must
 *       handle {@code ENOENT} on the rename; assert that the rotation does not attempt to open a
 *       new log file if the rename of the old file failed.
 *   <li>Assert that the application does not silently treat {@code ENOENT} from rename as a
 *       successful rename — the target path is unchanged and the expected content has not been made
 *       visible atomically.
 * </ul>
 *
 * <p>In production, {@code ENOENT} from {@code rename} on the source path occurs in TOCTOU races
 * where a concurrent cleanup daemon deletes the temporary file, when the application's temporary
 * file directory is different from the target directory and the temporary directory is cleaned up
 * by an external process, and when a symbolic link in the source path is removed between the
 * application's path resolution and the rename call.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code rename(2)} resolves both the source and destination paths before performing the atomic
 * directory update. If the source path does not exist at the time of the rename call, the kernel
 * returns {@code ENOENT}. No filesystem state is modified. Applications that assume the rename
 * always succeeds if the source file was successfully created are susceptible to TOCTOU races; they
 * must handle {@code ENOENT} from rename defensively.
 *
 * <p>Java's {@code Files.move(Path, Path, CopyOption...)} with {@code ATOMIC_MOVE} calls {@code
 * rename(2)} and throws a {@code NoSuchFileException} (for {@code ENOENT}) or an {@code
 * AtomicMoveNotSupportedException} (when the filesystem cannot perform the rename atomically).
 * Application code must catch both exception types.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosRenameFromEnoent(probability = 0.001)
 * class RenameFromEnoentTest {
 *   @Test
 *   void atomicFileUpdateReportsErrorWhenTemporaryFileIsMissing() {
 *     // assert that ENOENT on rename aborts the write sequence with an appropriate error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRenameFromEacces
 * @see ChaosRenameFromErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosRenameFromEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.RENAME_FROM, errno = Errno.ENOENT)
public @interface ChaosRenameFromEnoent {

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
   * @ChaosRenameFromEnoent(id = "primary",  probability = 0.001)
   * @ChaosRenameFromEnoent(id = "replica",  probability = 0.01)
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
    ChaosRenameFromEnoent[] value();
  }
}
