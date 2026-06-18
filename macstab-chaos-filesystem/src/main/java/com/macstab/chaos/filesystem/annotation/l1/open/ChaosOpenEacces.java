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
 * Injects {@code EACCES} into {@code open(2)}, causing the call to return {@code -1} with {@code
 * errno = EACCES} as if the calling process lacks the required permission to open the file — either
 * because the file mode bits deny access, a POSIX ACL entry denies the operation, or an LSM
 * (SELinux, AppArmor) policy vetoes the open.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code OPEN}, errno = {@code EACCES})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * open} call; when it fires the interposer returns {@code -1} with {@code errno = EACCES} without
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
 *       errno = EACCES}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EACCES} from {@code open} is a non-retriable error without remediation at the
 *       application layer; the process cannot change file permissions at runtime. Assert that the
 *       application emits a clear "permission denied" error message with the file path rather than
 *       retrying the open call indefinitely.
 *   <li>Applications that open configuration files, key files, or certificates at startup should
 *       handle {@code EACCES} with a startup-fail-fast path that reports the missing permission
 *       clearly; assert that the error message includes both the file path and the operation
 *       attempted ({@code O_RDONLY}, {@code O_RDWR}).
 *   <li>Log-writing paths that open log files should handle {@code EACCES} by falling back to
 *       stderr or a syslog appender; assert that the application does not silently drop log events
 *       when the log file cannot be opened.
 *   <li>Assert that {@code EACCES} on {@code open} for a lock file or PID file causes the
 *       application to fail with a clear message rather than proceeding without the lock (which
 *       could cause split-brain).
 * </ul>
 *
 * <p>In production, {@code EACCES} from {@code open} occurs when container security contexts are
 * misconfigured (SELinux labels, AppArmor profiles, seccomp filters, capability drops), when an
 * application attempts to open a file owned by another user, and when a Kubernetes secret or
 * configmap volume is mounted with incorrect file permissions.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's permission check for {@code open} proceeds in three stages: first, standard
 * discretionary access control (DAC) checks the file's owner, group, and world permission bits
 * against the calling process's effective UID and GID; second, POSIX ACLs (if present) are
 * evaluated; third, mandatory access control (MAC) modules such as SELinux or AppArmor evaluate
 * their policy. If any stage denies access, the kernel returns {@code EACCES}.
 *
 * <p>The specific flags passed to {@code open} affect which permissions are checked: {@code
 * O_RDONLY} requires read permission, {@code O_WRONLY} requires write permission, and {@code
 * O_RDWR} requires both. {@code O_CREAT} additionally requires write permission on the directory.
 * If the process passes {@code O_CREAT | O_EXCL} on a file that already exists and the process
 * lacks write permission, the kernel returns {@code EACCES} before checking whether the file
 * exists.
 *
 * <p>Java maps {@code EACCES} from {@code open} to a {@code FileNotFoundException} with the message
 * "Permission denied" (via {@code new FileInputStream("path")}) or to an {@code IOException}
 * subclass depending on the NIO channel used. The distinction between "file does not exist" ({@code
 * ENOENT}) and "file exists but is not accessible" ({@code EACCES}) is operationally significant —
 * both manifest as {@code FileNotFoundException} in some Java APIs, but the underlying cause and
 * remediation differ entirely.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosOpenEacces(probability = 0.1)
 * class OpenEaccesTest {
 *   @Test
 *   void applicationEmitsClearPermissionDeniedErrorOnConfigFileAccess() {
 *     // assert that the application fails fast with a clear message when config cannot be opened
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosOpenEnoent
 * @see ChaosOpenErofs
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosOpenEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.OPEN, errno = Errno.EACCES)
public @interface ChaosOpenEacces {

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
   * @ChaosOpenEacces(id = "primary",  probability = 0.001)
   * @ChaosOpenEacces(id = "replica",  probability = 0.01)
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
    ChaosOpenEacces[] value();
  }
}
