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
 * Injects {@code EROFS} into {@code write(2)}, causing the call to return {@code -1} with
 * {@code errno = EROFS} as if the file's filesystem has been remounted read-only and the kernel
 * cannot accept the write because no modifications are permitted on a read-only filesystem.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code WRITE}, errno = {@code EROFS})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code write} call; when it fires the interposer returns {@code -1} with {@code errno = EROFS}
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
 *       {@code errno = EROFS}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EROFS} from {@code write} on a previously-writable filesystem indicates that the
 *       filesystem was remounted read-only, typically due to disk errors detected by the kernel.
 *       Assert that the application detects this condition and triggers an immediate alert rather
 *       than silently failing writes.
 *   <li>Applications that open files for writing without {@code O_RDONLY} will receive {@code EROFS}
 *       on the first write after the filesystem is remounted read-only, even though the file was
 *       successfully opened before the remount. Assert that the application handles this mid-stream
 *       write failure without crashing or losing buffered data.
 *   <li>WAL implementations that write sequentially to a log file must detect {@code EROFS} on the
 *       write path and abort all in-flight transactions; assert that the WAL writer transitions to
 *       an error state that prevents new transactions from starting.
 *   <li>Assert that the application's health check detects write failures and returns a degraded
 *       status, enabling load balancers to redirect traffic away from the instance with a
 *       read-only filesystem.
 * </ul>
 *
 * <p>In production, {@code EROFS} from {@code write} occurs when the kernel remounts a filesystem
 * read-only after detecting unrecoverable I/O errors during writeback. The remount is logged in
 * {@code dmesg} with a message like "EXT4-fs error ... remounting filesystem read-only". The
 * application continues running but all writes to that filesystem will fail with {@code EROFS}
 * until the filesystem is manually repaired and remounted read-write.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's ext4 and XFS filesystems monitor the success of write operations and track
 * error counts in the filesystem's superblock. When a write fails due to a storage error and the
 * filesystem is configured with the {@code errors=remount-ro} mount option (the default for ext4),
 * the kernel automatically remounts the filesystem read-only to prevent further corruption.
 * Subsequent write calls are immediately rejected with {@code EROFS} without reaching the storage
 * device.
 *
 * <p>The read-only remount is transient — it persists until the filesystem is unmounted, repaired
 * with {@code fsck}, and remounted read-write. During this period, reads from pages in the page
 * cache succeed (the cache is not cleared on remount), but reads that require fetching new pages
 * from the storage device may also fail with {@code EIO} if the storage device itself is failing.
 *
 * <p>Java maps {@code EROFS} from {@code write} to an {@code IOException} with the message
 * "Read-only file system". The same message is produced for {@code EROFS} from {@code open};
 * the only difference is the operation context. Application code that catches {@code IOException}
 * and checks the message text should be aware that the message text varies across platforms
 * (glibc, musl, macOS) and JVM implementations.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosWriteErofs(probability = 1.0)
 * class WriteErofsTest {
 *   @Test
 *   void readOnlyFilesystemRemountTriggersImmediateAlertAndGracefulDegradation() {
 *     // assert that write EROFS is detected and the health check returns degraded status
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosOpenErofs
 * @see ChaosWriteEio
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosWriteErofs.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.WRITE, errno = Errno.EROFS)
public @interface ChaosWriteErofs {

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
   * @ChaosWriteErofs(id = "primary",  probability = 0.001)
   * @ChaosWriteErofs(id = "replica",  probability = 0.01)
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
    ChaosWriteErofs[] value();
  }
}
