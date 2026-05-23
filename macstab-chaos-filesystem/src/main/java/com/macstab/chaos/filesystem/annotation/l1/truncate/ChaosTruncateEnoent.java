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
 * Injects {@code ENOENT} into {@code truncate(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOENT} as if the named file does not exist or a component of the path prefix
 * is a directory that does not exist or is a dangling symbolic link.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code TRUNCATE}, errno = {@code ENOENT})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code truncate} call; when it fires the interposer returns {@code -1} with {@code errno = ENOENT}
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
 *       {@code errno = ENOENT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOENT} from {@code truncate} means the target file was not found at the given path;
 *       this is a TOCTOU (time-of-check, time-of-use) race condition scenario where the file was
 *       deleted or renamed between the application's existence check and the {@code truncate} call.
 *       Assert that the application re-creates the file or reports an appropriate error rather than
 *       assuming the file still exists.
 *   <li>Log rotation implementations that truncate the current log file after archiving it may
 *       encounter {@code ENOENT} if the file was already deleted by an external cleanup process;
 *       assert that the rotation handles this as a benign condition (the file is already gone) and
 *       proceeds to create a new log file.
 *   <li>Applications that use the "create, write, truncate to size, memory-map" pattern to prepare
 *       files for mmap must handle {@code ENOENT} on truncate if the file was removed between
 *       creation and truncation; assert that the application retries the entire sequence from
 *       creation rather than leaving a partially-prepared file in place.
 *   <li>Assert that the application distinguishes {@code ENOENT} (file absent) from {@code EACCES}
 *       (file inaccessible) and {@code EROFS} (filesystem read-only) to apply the correct
 *       remediation for each condition.
 * </ul>
 *
 * <p>In production, {@code ENOENT} from {@code truncate} occurs in TOCTOU races where an external
 * process deletes or renames the file between the application's check and its modification
 * operation, and when a Kubernetes ConfigMap or Secret is unmounted between the application's
 * configuration file discovery and its attempt to resize the file.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code truncate(2)} takes a path argument and resolves it through the VFS namespace at the
 * time of the call. If any component of the path has been removed or replaced since the
 * application last verified the path's existence, the call returns {@code ENOENT}. Unlike
 * {@code ftruncate(2)}, which uses an already-open file descriptor and is immune to path-level
 * changes, {@code truncate} is subject to the POSIX TOCTOU race condition.
 *
 * <p>The TOCTOU race between existence check and modification is particularly relevant for log
 * rotation, where an external log rotation daemon (logrotate, newsyslog) may rename the current
 * log file while the application's logger is about to truncate it. Applications should prefer
 * {@code ftruncate(2)} on an open file descriptor to avoid this race, or should be prepared to
 * handle {@code ENOENT} from {@code truncate} and recreate the file.
 *
 * <p>Java's {@code FileChannel.truncate(long)} uses {@code ftruncate(2)} via an open file
 * descriptor, so it is not affected by this annotation. Applications that call the path-based
 * truncate through JNI or through a native wrapper that uses {@code truncate(2)} directly are
 * affected.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosTruncateEnoent(probability = 0.001)
 * class TruncateEnoentTest {
 *   @Test
 *   void logRotationHandlesFileAbsenceByCreatingNewLogFile() {
 *     // assert that ENOENT on truncate causes a new log file to be created rather than an error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosTruncateEacces
 * @see ChaosTruncateErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosTruncateEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.TRUNCATE, errno = Errno.ENOENT)
public @interface ChaosTruncateEnoent {

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
   * @ChaosTruncateEnoent(id = "primary",  probability = 0.001)
   * @ChaosTruncateEnoent(id = "replica",  probability = 0.01)
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
    ChaosTruncateEnoent[] value();
  }
}
