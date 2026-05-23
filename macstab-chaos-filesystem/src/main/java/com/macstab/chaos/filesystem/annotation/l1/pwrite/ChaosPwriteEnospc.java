/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.pwrite;

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
 * Injects {@code ENOSPC} into {@code pwrite(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOSPC} as if the kernel could not allocate additional data blocks for the
 * positional write because the filesystem has no free blocks remaining.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code PWRITE}, errno = {@code ENOSPC})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code pwrite} call; when it fires the interposer returns {@code -1} with {@code errno = ENOSPC}
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
 *   <li>On each intercepted {@code pwrite} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ENOSPC}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOSPC} from {@code pwrite} means no additional blocks could be allocated for the
 *       write at the requested offset; the file's contents at that offset were not modified. Assert
 *       that the application does not proceed as if the write succeeded and reports the disk-full
 *       condition before attempting further writes.
 *   <li>Database engines that use {@code pwrite} for page writes must treat {@code ENOSPC} as a
 *       fatal write error; the engine must abort all in-progress transactions and refuse new write
 *       transactions until disk space is recovered. Assert that the database's health check
 *       transitions to a "disk full" state that prevents new write transactions from starting.
 *   <li>Applications that pre-allocate file space via {@code fallocate} to avoid {@code ENOSPC}
 *       during writes should be tested with this annotation to verify the pre-allocation is always
 *       performed before the first write; assert that {@code pwrite} never encounters {@code ENOSPC}
 *       when writing within the pre-allocated region.
 *   <li>Assert that the application emits a "disk full" alert with the affected mount point,
 *       enabling operators to take corrective action before the database becomes completely
 *       unavailable.
 * </ul>
 *
 * <p>In production, {@code ENOSPC} from {@code pwrite} occurs when a filesystem reaches 100% block
 * utilisation while a database engine is writing a page that extends the data file, when a
 * thin-provisioned volume runs out of backing store while the file's reported size still shows
 * available space, and when a concurrent process consumes the last available blocks between a
 * successful {@code fallocate} check and the subsequent {@code pwrite}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code pwrite(2)} writes to a caller-specified offset without modifying the file's current
 * position. When the write extends the file beyond its current size, the kernel must allocate new
 * data blocks. When the write falls within the file's current size but targets a sparse (unallocated)
 * region, the kernel must also allocate data blocks to back the sparse region. In both cases,
 * block exhaustion causes {@code ENOSPC}. Overwriting existing allocated blocks never causes
 * {@code ENOSPC} because no new allocation is needed.
 *
 * <p>Database storage engines use {@code pwrite} for in-place page updates (updating an existing
 * B-tree page at its known file offset). These writes overwrite existing blocks and should not
 * normally encounter {@code ENOSPC}. However, engines that use shadow-paging (writing new pages
 * to new offsets and updating a page table) do require block allocation and can encounter
 * {@code ENOSPC}. This annotation exercises the disk-full handling in both patterns.
 *
 * <p>Java's {@code FileChannel.write(ByteBuffer, long)} maps to {@code pwrite(2)} on Linux. When
 * the underlying call returns {@code ENOSPC}, the JVM throws an {@code IOException} with the
 * message "No space left on device". Application code that catches this exception should distinguish
 * it from {@code EDQUOT} ("Disk quota exceeded") and {@code EIO} ("Input/output error") to apply
 * the correct remediation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosPwriteEnospc(probability = 0.001)
 * class PwriteEnospcTest {
 *   @Test
 *   void diskFullOnPageWriteTransitionsDatabaseToReadOnlyMode() {
 *     // assert that ENOSPC on pwrite causes the database to refuse new write transactions
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosWriteEnospc
 * @see ChaosPwriteEdquot
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosPwriteEnospc.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.PWRITE, errno = Errno.ENOSPC)
public @interface ChaosPwriteEnospc {

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
   * @ChaosPwriteEnospc(id = "primary",  probability = 0.001)
   * @ChaosPwriteEnospc(id = "replica",  probability = 0.01)
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
    ChaosPwriteEnospc[] value();
  }
}
