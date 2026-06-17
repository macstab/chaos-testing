/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.open;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteEnospc;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code ENOSPC} into {@code open(2)} with {@code O_CREAT}, causing the call to return
 * {@code -1} with {@code errno = ENOSPC} as if the filesystem has no free blocks or inodes to
 * allocate a new directory entry and inode for the file being created.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code OPEN}, errno = {@code ENOSPC})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * open} call; when it fires the interposer returns {@code -1} with {@code errno = ENOSPC} without
 * performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code open} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = ENOSPC}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOSPC} from {@code open} occurs when creating a new file ({@code O_CREAT}) on a
 *       full filesystem; opening an existing file for reading or writing does not allocate blocks
 *       and typically succeeds even on a full filesystem. Assert that the application treats open
 *       ENOSPC as a disk-full condition and triggers disk-pressure cleanup or log rotation.
 *   <li>Applications that create temporary files for atomic writes (write to temp, then rename)
 *       must handle {@code ENOSPC} on the temp-file creation path and propagate the disk-full error
 *       to the caller rather than leaving partial data behind.
 *   <li>Log rotation scripts that create new log files on a full disk will receive {@code ENOSPC};
 *       assert that the application's logger falls back to stderr or drops logs with a rate-limited
 *       warning rather than entering an infinite retry loop that fills the inode table.
 *   <li>Assert that the application emits a disk-pressure alert when it first encounters {@code
 *       ENOSPC} from {@code open}, enabling operators to trigger cleanup before data loss occurs.
 * </ul>
 *
 * <p>In production, {@code ENOSPC} from {@code open} on file creation occurs when the application's
 * log volume fills up (unbounded log growth, failed log rotation), when a database's data volume
 * has no room for new table or index files, and when a containerized process creates many small
 * temporary files that collectively exhaust the overlay filesystem's inode table.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ENOSPC} can be returned from {@code open(O_CREAT)} for two distinct reasons: block
 * exhaustion (no free data blocks to store the file's contents) and inode exhaustion (no free
 * inodes to represent the file's metadata). Both conditions are reported as {@code ENOSPC} with no
 * way to distinguish them from the errno alone; {@code df -i} (inode usage) and {@code df -h}
 * (block usage) are needed to identify the cause.
 *
 * <p>Ext4 reserves 5% of blocks for the root user by default (tunable with {@code tune2fs -m});
 * this reservation means that unprivileged processes receive {@code ENOSPC} when the filesystem is
 * 95% full, while root processes can still write. Containerized processes typically run as
 * non-root, so they will encounter the 95% threshold even though the filesystem is not completely
 * full. This injection simulates the condition without waiting for the disk to fill.
 *
 * <p>Java maps {@code ENOSPC} from {@code open} to a {@code FileNotFoundException} or {@code
 * IOException} with the message "No space left on device". The exception class depends on whether
 * the Java API being used maps all open failures to {@code FileNotFoundException} or distinguishes
 * creation failures from access failures. {@code Files.createFile(path)} throws {@code
 * IOException("No space left on device")} rather than {@code FileNotFoundException}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosOpenEnospc(probability = 0.1)
 * class OpenEnospcTest {
 *   @Test
 *   void diskFullOnTempFileCreationPropagatesToCallerWithClearError() {
 *     // assert that the caller receives a disk-full error rather than a data loss silently
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosWriteEnospc
 * @see ChaosOpenErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosOpenEnospc.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.OPEN, errno = Errno.ENOSPC)
public @interface ChaosOpenEnospc {

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
   * @ChaosOpenEnospc(id = "primary",  probability = 0.001)
   * @ChaosOpenEnospc(id = "replica",  probability = 0.01)
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
    ChaosOpenEnospc[] value();
  }
}
