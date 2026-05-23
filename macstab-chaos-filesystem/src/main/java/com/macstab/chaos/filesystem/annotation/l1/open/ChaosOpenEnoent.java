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
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code ENOENT} into {@code open(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOENT} as if the requested file or a directory component of the path does not
 * exist on the filesystem.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code OPEN}, errno = {@code ENOENT})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code open} call; when it fires the interposer returns {@code -1} with {@code errno = ENOENT}
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
 *   <li>On each intercepted {@code open} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ENOENT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOENT} from {@code open} on a file that should exist indicates a missing
 *       deployment artifact; without {@code O_CREAT}, the kernel will not create the file.
 *       Assert that the application reports a clear "file not found" error with the full path
 *       rather than a generic IO failure.
 *   <li>Applications that open configuration files or secret files mounted from Kubernetes
 *       configmaps or secrets must handle {@code ENOENT} at startup by failing fast and emitting
 *       a startup error that identifies the missing file; assert that the error message includes
 *       the expected path and the mount point.
 *   <li>Applications that use atomic write patterns (write to tmp file, then rename) should handle
 *       {@code ENOENT} on the rename source path if the tmp file was deleted by another process;
 *       assert that the data is not silently lost when the rename source disappears.
 *   <li>Log rotation that moves or deletes log files while the application has them open may cause
 *       subsequent {@code open} calls for new log files to return {@code ENOENT} if the log
 *       directory was deleted; assert that the logger recreates the directory.
 * </ul>
 *
 * <p>In production, {@code ENOENT} from {@code open} occurs when configuration files are deleted
 * while the application is running (during a bad deployment or manual operator intervention),
 * when a Kubernetes secret or configmap volume is unmounted, when a symlink target is removed
 * ({@code ENOENT} is returned rather than {@code ENOLINK}), and when a file is removed by another
 * process between the time the application checked for its existence and the time it attempted to
 * open it (time-of-check-time-of-use race).
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's pathname resolution ({@code path_openat}) walks the directory tree component by
 * component. If any component does not exist, the kernel returns {@code ENOENT}. The error is
 * returned regardless of whether the missing component is the final filename or an intermediate
 * directory. An application cannot distinguish these two cases from the errno alone; the path
 * component that triggered the error is not reported.
 *
 * <p>The {@code O_CREAT} flag changes the semantics: with {@code O_CREAT}, if the file does not
 * exist the kernel creates it (subject to write permission on the parent directory). Without
 * {@code O_CREAT}, {@code ENOENT} is returned for any missing path component. If {@code O_CREAT}
 * is specified but an intermediate directory component is missing, the kernel still returns
 * {@code ENOENT} — {@code open} does not create missing directories.
 *
 * <p>Java maps {@code ENOENT} from {@code open} to {@code FileNotFoundException} with the message
 * "No such file or directory". This is the most common {@code FileNotFoundException} variant but
 * is sometimes confused with the {@code EMFILE} variant which uses the same exception class.
 * Application code that needs to distinguish missing files from permission errors should catch
 * the exception and inspect the message or use {@code Files.exists(path)} before opening, though
 * the latter introduces a TOCTOU race.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosOpenEnoent(probability = 0.1)
 * class OpenEnoentTest {
 *   @Test
 *   void missingConfigFileIsReportedWithClearPathInErrorMessage() {
 *     // assert that the startup error includes the expected config path
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosOpenEacces
 * @see ChaosOpenErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosOpenEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.OPEN, errno = Errno.ENOENT)
public @interface ChaosOpenEnoent {

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
   * @ChaosOpenEnoent(id = "primary",  probability = 0.001)
   * @ChaosOpenEnoent(id = "replica",  probability = 0.01)
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
    ChaosOpenEnoent[] value();
  }
}
