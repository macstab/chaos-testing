/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.truncate;

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
 * Injects {@code ENOSPC} into {@code truncate(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOSPC} as if the kernel could not allocate additional blocks needed to extend
 * the file to the requested size because the filesystem has no free blocks remaining.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code TRUNCATE}, errno = {@code ENOSPC})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code truncate} call; when it fires the interposer returns {@code -1} with {@code errno = ENOSPC}
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
 *   <li>On each intercepted {@code truncate} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ENOSPC}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOSPC} from {@code truncate} when extending a file means the filesystem cannot
 *       allocate the blocks needed to represent the new file size; the file's size is unchanged.
 *       Assert that the application does not proceed as if the file was successfully extended and
 *       reports the disk-full condition rather than attempting to write to the unextended file.
 *   <li>Applications that use {@code truncate} to pre-size files for memory-mapped access (setting
 *       the file to the expected mmap size before mapping it) must handle {@code ENOSPC} gracefully;
 *       assert that the application does not attempt to mmap a file that was not successfully sized.
 *   <li>WAL pre-allocation patterns that use {@code truncate} to extend the WAL file to a fixed
 *       size at startup must handle {@code ENOSPC}; assert that the database fails to start with a
 *       clear "disk full" error rather than starting with a truncated WAL file that causes
 *       subsequent writes to fail unexpectedly.
 *   <li>Assert that the application does not treat truncate-time {@code ENOSPC} as a benign
 *       condition that can be ignored — unlike shrinking a file (which never needs new blocks),
 *       extending a file via truncate requires block allocation.
 * </ul>
 *
 * <p>In production, {@code ENOSPC} from {@code truncate} when extending a file occurs when the
 * filesystem is full and the application attempts to pre-allocate space (often as part of WAL
 * file creation or memory-mapped file setup), and on sparse files when the application sets the
 * file size to a very large value without having free blocks to back the sparse regions.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code truncate(2)} can both shrink and extend files. When shrinking a file (new length
 * smaller than current size), the kernel frees the blocks that back the truncated region and no
 * new allocation is needed; {@code ENOSPC} is not possible for shrink operations. When extending
 * a file (new length larger than current size), the kernel must allocate blocks to back the new
 * region (or create a sparse region on filesystems that support it). If the filesystem has no
 * free blocks, extending via {@code truncate} returns {@code ENOSPC}.
 *
 * <p>On filesystems with sparse file support (ext4, XFS, Btrfs), extending via {@code truncate}
 * may not allocate blocks immediately — the hole is represented as a sparse region and blocks
 * are only allocated when the sparse region is written. In this case, {@code truncate} may succeed
 * even when the filesystem is nearly full, and {@code ENOSPC} only appears when the application
 * writes to the sparse region. This injection bypasses the sparse file optimisation and injects
 * {@code ENOSPC} at the truncate call itself.
 *
 * <p>Java's {@code FileChannel.truncate(long)} calls {@code ftruncate(2)} via an open file
 * descriptor and throws an {@code IOException} with the message "No space left on device" when the
 * underlying call returns {@code ENOSPC} on an extend operation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosTruncateEnospc(probability = 0.001)
 * class TruncateEnospcTest {
 *   @Test
 *   void walPreAllocationFailurePreventsDatabaseStartup() {
 *     // assert that ENOSPC on truncate during WAL pre-allocation fails startup with a clear error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosTruncateErofs
 * @see ChaosAllocateEnospc
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosTruncateEnospc.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.TRUNCATE, errno = Errno.ENOSPC)
public @interface ChaosTruncateEnospc {

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
   * @ChaosTruncateEnospc(id = "primary",  probability = 0.001)
   * @ChaosTruncateEnospc(id = "replica",  probability = 0.01)
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
    ChaosTruncateEnospc[] value();
  }
}
