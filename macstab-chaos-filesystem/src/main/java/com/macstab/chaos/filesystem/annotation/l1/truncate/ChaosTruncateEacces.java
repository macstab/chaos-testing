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
 * Injects {@code EACCES} into {@code truncate(2)}, causing the call to return {@code -1} with
 * {@code errno = EACCES} as if the calling process does not have write permission on the file or
 * one of the path components requires search permission that the process does not have.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code TRUNCATE}, errno = {@code EACCES})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code truncate} call; when it fires the interposer returns {@code -1} with {@code errno = EACCES}
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
 *       {@code errno = EACCES}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EACCES} from {@code truncate} means the process cannot change the file's size due
 *       to a permission check failure; the file's contents are unchanged. Assert that the
 *       application handles this as a permission error rather than a disk error, and that the
 *       error message includes the file path to aid operator diagnosis.
 *   <li>Log rotation implementations that use {@code truncate} to reset log file size after
 *       archiving must handle {@code EACCES} if the running process does not have write permission
 *       on the log file (e.g., after a configuration change drops the log file's write permission).
 *       Assert that the rotation fails gracefully rather than crashing the log writer.
 *   <li>Applications that use {@code truncate} to pre-size files for memory-mapped access must
 *       handle {@code EACCES} if the file's permissions change between creation and truncation;
 *       assert that the application retries the operation with appropriate error recovery rather
 *       than silently operating on an unsized file.
 *   <li>Assert that the application's error path on truncate permission failure correctly
 *       distinguishes between "file not found" ({@code ENOENT}), "permission denied" ({@code EACCES}),
 *       and "read-only filesystem" ({@code EROFS}) and applies the appropriate remediation.
 * </ul>
 *
 * <p>In production, {@code EACCES} from {@code truncate} occurs when a container's security
 * policy (SELinux context, AppArmor profile, capability restriction) prevents the process from
 * modifying a file's size even though the file's DAC permissions would otherwise allow it, and
 * when a file's permissions are changed by an external process between the time the application
 * opens the directory and the time it calls {@code truncate}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code truncate(2)} takes a path argument and changes the named file's size to the specified
 * length, unlike {@code ftruncate(2)} which takes an open file descriptor. Because {@code truncate}
 * accesses the file by path, it performs the full VFS permission check: DAC check on each path
 * component (execute permission required for directory traversal), write permission check on the
 * file's inode, and LSM (SELinux, AppArmor) policy check. A failure at any of these levels
 * returns {@code EACCES}.
 *
 * <p>The path-based permission check in {@code truncate} is subject to TOCTOU (time-of-check,
 * time-of-use) races: a directory's permissions may change between the application's {@code access()}
 * check and the subsequent {@code truncate} call. This injection simulates the race outcome
 * without requiring an actual concurrent permission change.
 *
 * <p>Java's {@code FileChannel.truncate(long)} uses {@code ftruncate(2)} (the fd-based variant)
 * rather than {@code truncate(2)}, so it is not affected by this annotation. Applications that
 * call the path-based truncate through JNI or through a native wrapper are affected.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosTruncateEacces(probability = 0.001)
 * class TruncateEaccesTest {
 *   @Test
 *   void logRotationHandlesPermissionDeniedGracefullyWithoutCrash() {
 *     // assert that EACCES on truncate causes a graceful rotation failure, not a crash
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosTruncateEnoent
 * @see ChaosTruncateErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosTruncateEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.TRUNCATE, errno = Errno.EACCES)
public @interface ChaosTruncateEacces {

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
   * @ChaosTruncateEacces(id = "primary",  probability = 0.001)
   * @ChaosTruncateEacces(id = "replica",  probability = 0.01)
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
    ChaosTruncateEacces[] value();
  }
}
