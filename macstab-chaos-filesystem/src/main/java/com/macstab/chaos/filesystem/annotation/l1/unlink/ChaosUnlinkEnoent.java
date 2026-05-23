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
 * Injects {@code ENOENT} into {@code unlink(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOENT} as if the named file does not exist or a component of the path prefix
 * does not exist — simulating a TOCTOU race where the file was deleted by another process between
 * the application's existence check and its deletion attempt.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code UNLINK}, errno = {@code ENOENT})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code unlink} call; when it fires the interposer returns {@code -1} with {@code errno = ENOENT}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code unlink} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ENOENT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOENT} from {@code unlink} is often a benign condition: the file was already
 *       deleted by another process or by a previous cleanup pass. Assert that the application
 *       treats {@code ENOENT} from unlink as a successful deletion (the file is gone, which was
 *       the goal) and does not propagate it as an error to callers.
 *   <li>Cleanup loops that delete a list of temporary files must handle {@code ENOENT} for each
 *       file without aborting the loop; assert that the cleanup continues to delete the remaining
 *       files in the list even when some are already gone.
 *   <li>The "delete-on-close" pattern (unlink the file immediately after opening it, keeping the
 *       file accessible only through the open file descriptor) relies on the unlink succeeding;
 *       assert that an {@code ENOENT} on the immediate unlink is treated as an error because
 *       the file should definitely exist at this point.
 *   <li>Assert that the application's idempotent delete logic (used in distributed cleanup
 *       scenarios) correctly treats {@code ENOENT} as a success rather than a failure requiring
 *       retry.
 * </ul>
 *
 * <p>In production, {@code ENOENT} from {@code unlink} occurs in TOCTOU races where an external
 * cleanup daemon removes the file between the application's discovery and deletion, in concurrent
 * cleanup scenarios where multiple processes attempt to delete the same temporary file, and when
 * a file's containing directory is removed before the file itself is deleted.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code unlink(2)} removes a directory entry by decrementing the inode's link count and
 * removing the name from the directory. If the link count reaches zero and no process has the
 * file open, the inode and its data blocks are freed. If the link count reaches zero but processes
 * still have the file open, the directory entry is removed (the file becomes unlinked) but the
 * inode and data blocks are not freed until all file descriptors are closed.
 *
 * <p>{@code ENOENT} from {@code unlink} means the directory entry was not found; the file does
 * not exist or was already removed. Unlike most other error conditions, {@code ENOENT} from
 * unlink often indicates that the desired outcome (the file is gone) has already been achieved
 * by another means. Applications should distinguish between "expected to exist, does not" (error)
 * and "may or may not exist" (success if absent).
 *
 * <p>Java's {@code Files.delete(Path)} throws a {@code NoSuchFileException} (a subtype of
 * {@code IOException}) on {@code ENOENT}. {@code Files.deleteIfExists(Path)} returns {@code false}
 * on {@code ENOENT} without throwing, which is the appropriate idiom for idempotent deletion.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosUnlinkEnoent(probability = 0.001)
 * class UnlinkEnoentTest {
 *   @Test
 *   void cleanupLoopContinuesDeletingRemainingFilesWhenSomeAreAlreadyGone() {
 *     // assert that ENOENT on unlink is treated as success and the loop continues
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosUnlinkEacces
 * @see ChaosUnlinkErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosUnlinkEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.UNLINK, errno = Errno.ENOENT)
public @interface ChaosUnlinkEnoent {

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
   * @ChaosUnlinkEnoent(id = "primary",  probability = 0.001)
   * @ChaosUnlinkEnoent(id = "replica",  probability = 0.01)
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
    ChaosUnlinkEnoent[] value();
  }
}
