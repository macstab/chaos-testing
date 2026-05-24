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
 * Injects {@code EROFS} into {@code rename(2)} as observed from the destination (new) path, causing
 * the call to return {@code -1} with {@code errno = EROFS} as if the filesystem containing the
 * destination directory has been remounted read-only and the kernel cannot create the new directory
 * entry.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RENAME_TO}, errno = {@code
 * EROFS}) tuple. The {@code RENAME_TO} operation models the destination-path permission check of
 * {@code rename(2)}: the VFS must be able to add the new name to the destination directory, which
 * requires write access to that directory — impossible on a read-only filesystem. A Bernoulli trial
 * with probability {@link #probability} is run on each intercepted {@code rename} call; when it
 * fires the interposer returns {@code -1} with {@code errno = EROFS} without performing any real
 * kernel operation.
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
 *       errno = EROFS}, simulating a read-only filesystem blocking the destination-directory write.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EROFS} from {@code rename} on the destination path means the filesystem was
 *       remounted read-only; no directory modification can succeed until it is repaired and
 *       remounted read-write. Assert that the application treats this as a fatal filesystem-state
 *       error rather than a transient path-level failure.
 *   <li>The "write temporary → rename to final path" atomic update pattern fails when the
 *       destination directory is on a read-only filesystem; the source (temporary) file still
 *       exists and the destination is unchanged. Assert that the application cleans up the
 *       temporary file and alerts operators rather than leaving orphan temporaries.
 *   <li>Applications that move completed work files from a staging directory to an output directory
 *       must handle {@code EROFS} on the output directory; assert that the staging file is
 *       preserved (not deleted) and that a health-check or metrics endpoint reflects the degraded
 *       write capability.
 *   <li>Assert that the error path identifies the destination filesystem as the failing component
 *       and does not misattribute the failure to the source file or a permission policy.
 * </ul>
 *
 * <p>In production, {@code EROFS} from {@code rename} on the destination path is identical in cause
 * to the source-path variant: the kernel has remounted the filesystem read-only after detecting
 * write errors. The distinction between {@code RENAME_FROM} and {@code RENAME_TO} matters only when
 * source and destination are on different filesystems (cross-device rename, which fails with {@code
 * EXDEV} in normal operation) — on the same filesystem both checks occur atomically within a single
 * VFS transaction.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code rename(2)} within a single filesystem is a single atomic VFS transaction that removes
 * the source name and installs the destination name in a single journal commit. The kernel checks
 * writability of both the source directory (to remove the old entry) and the destination directory
 * (to add the new entry) before committing the transaction. If either check fails with {@code
 * EROFS}, the entire rename fails atomically — no partial state is possible.
 *
 * <p>When source and destination are on different filesystems {@code rename(2)} fails with {@code
 * EXDEV}; applications using the write-temporary-then-rename pattern must handle both {@code EROFS}
 * and {@code EXDEV} on the rename call, as well as the cleanup of the temporary file in both cases.
 *
 * <p>Java's {@code Files.move(Path, Path, ATOMIC_MOVE)} wraps {@code rename(2)} and throws {@code
 * AtomicMoveNotSupportedException} for {@code EXDEV} but {@code IOException("Read-only file
 * system")} for {@code EROFS}. Application code that catches {@code IOException} from {@code
 * Files.move} must inspect the cause to distinguish a read-only filesystem (requires operator
 * intervention) from a transient I/O error (may be retried).
 *
 * <p>Compared with {@link ChaosRenameToEacces}: {@code EROFS} signals that the entire destination
 * filesystem is read-only (structural failure); {@code EACCES} signals that the specific
 * destination directory denied write access (policy failure, potentially fixable by changing
 * permissions). Use {@code EROFS} to test the global degradation path; use {@code EACCES} to test
 * per-directory ACL handling.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosRenameToErofs(probability = 1.0)
 * class RenameToErofsTest {
 *   @Test
 *   void outputDirectoryReadOnlyPreservesSourceAndAlertsOperator() {
 *     // assert that EROFS on rename to output path leaves source intact
 *     // and that health check returns a degraded write-capability status
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRenameToEacces
 * @see ChaosRenameFromErofs
 * @see ChaosWriteErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosRenameToErofs.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.RENAME_TO, errno = Errno.EROFS)
public @interface ChaosRenameToErofs {

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
   * @ChaosRenameToErofs(id = "primary",  probability = 0.001)
   * @ChaosRenameToErofs(id = "replica",  probability = 0.01)
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
    ChaosRenameToErofs[] value();
  }
}
