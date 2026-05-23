/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.write;

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
 * Injects {@code ENOSPC} into {@code write(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOSPC} as if the kernel could not allocate additional data blocks for the write
 * because the filesystem has no free blocks remaining.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code WRITE}, errno = {@code ENOSPC})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code write} call; when it fires the interposer returns {@code -1} with {@code errno = ENOSPC}
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
 *   <li>On each intercepted {@code write} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ENOSPC}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOSPC} from {@code write} means the filesystem is full and no additional data can
 *       be written; any partial data already written to the kernel page cache in previous calls may
 *       still be present. Assert that the application does not proceed as if the write succeeded —
 *       it must abort the current operation and report the disk-full condition.
 *   <li>Log-writing paths (application loggers, audit trails, access logs) must handle {@code ENOSPC}
 *       by triggering an emergency log rotation or falling back to stderr; assert that the logger
 *       does not silently discard log entries or enter an infinite retry loop that fills the disk
 *       faster.
 *   <li>WAL implementations must handle {@code ENOSPC} on WAL writes by aborting all in-progress
 *       transactions and refusing new writes; assert that the database does not mark a transaction
 *       as committed when the WAL write failed due to disk exhaustion.
 *   <li>Assert that the application emits a "disk full" alert with the affected filesystem's mount
 *       point and the amount of space consumed, enabling operators to identify which tenant or
 *       component is consuming storage and take corrective action (log rotation, data archival,
 *       disk expansion).
 * </ul>
 *
 * <p>In production, {@code ENOSPC} from {@code write} occurs when a filesystem reaches 100% block
 * utilisation, when an ext4 filesystem's 5% reserved-blocks threshold is reached by an unprivileged
 * process (even though the disk appears 95% full by {@code df -h}), and when a thin-provisioned
 * LVM or cloud volume runs out of backing store while the filesystem's reported capacity still shows
 * free space.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>For buffered writes, the kernel accepts data into the page cache immediately without allocating
 * blocks on the storage device. Block allocation happens lazily during writeback when the dirty
 * pages are flushed. {@code ENOSPC} during this lazy allocation is delivered to the application on
 * the next {@code write} or {@code fsync} call, not on the write that caused the allocation. This
 * deferred error delivery means the application may have issued many successful {@code write} calls
 * before receiving {@code ENOSPC}, and the data from those calls may not have been persisted.
 *
 * <p>For direct I/O ({@code O_DIRECT}) and synchronous writes ({@code O_SYNC} or {@code O_DSYNC}),
 * block allocation is synchronous and {@code ENOSPC} is returned immediately on the write that
 * would require new blocks. This injection simulates the synchronous path regardless of the file's
 * open flags.
 *
 * <p>The ext4 filesystem reserves 5% of total blocks (configurable via {@code tune2fs -m}) for
 * the root user. When a non-root process's writes would consume the reserved blocks, the kernel
 * returns {@code ENOSPC} even though {@code df} shows free space. This is a common source of
 * confusion in production: operators see "5% free" but applications report disk full. This injection
 * bypasses the reservation logic and can be used to test disk-full handling without requiring a
 * full filesystem.
 *
 * <p>Java maps {@code ENOSPC} from {@code write} to an {@code IOException} with the message
 * "No space left on device". {@code FileOutputStream.write()} and {@code FileChannel.write()} both
 * propagate this as an {@code IOException}. Application code that catches {@code IOException} and
 * inspects the message text should be aware that the message varies across platforms (glibc, musl,
 * macOS) and that {@code ENOSPC} and {@code EDQUOT} produce different messages ("No space left on
 * device" vs "Disk quota exceeded").
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosWriteEnospc(probability = 0.001)
 * class WriteEnospcTest {
 *   @Test
 *   void diskFullTriggersEmergencyLogRotationAndAlert() {
 *     // assert that ENOSPC on write triggers log rotation and a "disk full" alert
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosWriteEdquot
 * @see ChaosFsyncEnospc
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosWriteEnospc.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.WRITE, errno = Errno.ENOSPC)
public @interface ChaosWriteEnospc {

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
   * @ChaosWriteEnospc(id = "primary",  probability = 0.001)
   * @ChaosWriteEnospc(id = "replica",  probability = 0.01)
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
    ChaosWriteEnospc[] value();
  }
}
