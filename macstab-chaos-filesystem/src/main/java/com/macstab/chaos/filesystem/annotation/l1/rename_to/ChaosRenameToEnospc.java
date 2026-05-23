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
 * Injects {@code ENOSPC} into {@code rename(2)} as observed from the destination (new) path, causing
 * the call to return {@code -1} with {@code errno = ENOSPC} as if the filesystem has no space left
 * to allocate additional directory blocks for the new entry in the destination directory.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RENAME_TO}, errno = {@code ENOSPC})
 * tuple. The {@code RENAME_TO} operation models the destination-path space check of {@code rename(2)}:
 * when the destination directory must grow to accommodate a new entry and the filesystem has no free
 * blocks to extend it, the kernel returns {@code ENOSPC}. A Bernoulli trial with probability
 * {@link #probability} is run on each intercepted {@code rename} call; when it fires the interposer
 * returns {@code -1} with {@code errno = ENOSPC} without performing any real kernel operation.
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
 *       {@code errno = ENOSPC}, simulating a disk-full condition blocking the destination-directory
 *       extension.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOSPC} from {@code rename} on the destination path means the destination directory
 *       cannot grow; the source file remains at its original location and the destination is
 *       unchanged. Assert that the application cleans up the source (temporary) file and reports the
 *       disk-full condition rather than leaving orphan files on a full filesystem.
 *   <li>The "write-to-temporary-then-rename" atomic update pattern leaves the target file in its
 *       previous state on {@code ENOSPC}; assert that the application falls back to the previous
 *       content and notifies callers that the update was not applied.
 *   <li>Applications that continuously write output files to a shared directory (log writers, report
 *       generators) will eventually hit {@code ENOSPC} when the directory is full; assert that the
 *       application does not silently drop data but surfaces a "disk full" alert to operations.
 *   <li>Assert that the application does not retry the rename without first verifying that free
 *       space has been reclaimed — a retry loop on a full filesystem will spin until a timeout.
 * </ul>
 *
 * <p>In production, {@code ENOSPC} from {@code rename} on the destination path is rare on filesystems
 * where directories are not size-limited (most modern Linux filesystems extend directories
 * dynamically), but occurs when the filesystem itself is genuinely full and cannot allocate the
 * directory block needed to store the new entry. On HTree-indexed ext4 directories with many
 * existing entries, adding an entry may require allocating an additional leaf block.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code rename(2)} within a single filesystem modifies both the source and destination
 * directories in a single atomic VFS transaction. For most renames to an existing destination
 * entry (overwrite), no new directory block is needed — the existing destination slot is reused.
 * For renames to a new destination name in a full directory block, the filesystem must allocate
 * a new directory block before committing the rename. If the filesystem is full at this point,
 * the kernel returns {@code ENOSPC} and the rename fails atomically without modifying any
 * directory state.
 *
 * <p>On ext4 with HTree (htree) indexing enabled (the default for large directories), directory
 * entries are stored in a B-tree; adding a new name to a full leaf node requires allocating a new
 * leaf block. On a filesystem with no free blocks, this allocation fails with {@code ENOSPC}.
 * Non-indexed small directories store entries linearly in a single block and cannot overflow in
 * the same way until the single block is full.
 *
 * <p>Java's {@code Files.move(Path, Path, CopyOption...)} with {@code ATOMIC_MOVE} throws an
 * {@code IOException} with the message "No space left on device" when the underlying rename call
 * returns {@code ENOSPC}. Application code that catches this exception should check whether the
 * {@code ENOSPC} originated from the destination directory being full (metadata space) or from the
 * data file being full (data space), though in practice the two are indistinguishable at the Java
 * exception level.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosRenameToEnospc(probability = 0.001)
 * class RenameToEnospcTest {
 *   @Test
 *   void atomicUpdateCleansTempFileAndAlertsDiskFull() {
 *     // assert that ENOSPC on rename to target removes temp file and surfaces disk-full alert
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRenameToEacces
 * @see ChaosWriteEnospc
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosRenameToEnospc.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.RENAME_TO, errno = Errno.ENOSPC)
public @interface ChaosRenameToEnospc {

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
   * @ChaosRenameToEnospc(id = "primary",  probability = 0.001)
   * @ChaosRenameToEnospc(id = "replica",  probability = 0.01)
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
    ChaosRenameToEnospc[] value();
  }
}
