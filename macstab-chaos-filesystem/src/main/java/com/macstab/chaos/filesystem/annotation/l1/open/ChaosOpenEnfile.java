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
 * Injects {@code ENFILE} into {@code open(2)}, causing the call to return {@code -1} with {@code
 * errno = ENFILE} as if the system-wide open file count has reached the kernel's global limit
 * ({@code fs.file-max}) and no new file descriptors can be allocated by any process on the host.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code OPEN}, errno = {@code ENFILE})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * open} call; when it fires the interposer returns {@code -1} with {@code errno = ENFILE} without
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
 *       errno = ENFILE}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENFILE} from {@code open} is a system-wide exhaustion condition affecting all
 *       processes on the host; unlike {@code EMFILE} (per-process limit), it cannot be resolved by
 *       the application closing its own file descriptors. Assert that the application treats {@code
 *       ENFILE} as a temporary host-wide shortage and backs off rather than failing permanently.
 *   <li>Assert that the application distinguishes {@code ENFILE} from {@code EMFILE} in its error
 *       reporting — the remediation is different (host-level {@code sysctl fs.file-max} vs.
 *       process-level {@code ulimit -n}), and mixing the two in a "too many open files" catch-all
 *       delays incident response.
 *   <li>Startup sequences that open multiple configuration files, certificate stores, and log files
 *       concurrently must handle {@code ENFILE} by serializing the opens and retrying with
 *       back-off; assert that the startup does not fail permanently on transient host fd
 *       exhaustion.
 *   <li>Assert that the application emits a "system file descriptor limit reached" metric or alert
 *       with the current {@code /proc/sys/fs/file-max} value, enabling operators to tune the host
 *       limit or identify the tenant causing the exhaustion.
 * </ul>
 *
 * <p>In production, {@code ENFILE} from {@code open} occurs on Kubernetes nodes where multiple
 * containers collectively exhaust the host's {@code fs.file-max} limit. Because the limit is shared
 * across all cgroups on the node, a traffic spike on one container can cause {@code ENFILE} for
 * unrelated workloads on the same node — a noisy-neighbor failure mode that is difficult to
 * diagnose without host-level monitoring.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's global file table ({@code file_struct}) tracks all open file descriptions across
 * all processes. When the count reaches {@code /proc/sys/fs/file-max}, {@code get_empty_filp()}
 * (called by {@code do_filp_open} during {@code open(2)} processing) returns NULL and the kernel
 * returns {@code ENFILE} to the calling process. This check is performed before the filesystem
 * layer is involved; the specific file type (regular file, socket, pipe) does not affect the check.
 *
 * <p>The distinction between file descriptions and file descriptors is important: a file
 * description is a kernel-internal object (referenced via {@code struct file}); a file descriptor
 * is a per-process integer that references a file description. {@code dup(2)} creates a new file
 * descriptor pointing to the same file description; the count in {@code /proc/sys/fs/file-nr}
 * counts file descriptions (not descriptors). {@code ENFILE} is triggered when file descriptions
 * are exhausted; {@code EMFILE} is triggered when a process's file descriptors are exhausted.
 *
 * <p>Java maps {@code ENFILE} from {@code open} to a {@code FileNotFoundException} with the message
 * "Too many open files in system". This message is generated by {@code strerror(ENFILE)} in glibc;
 * the "in system" suffix distinguishes it from the per-process {@code EMFILE} message "Too many
 * open files", though the distinction is easy to miss in logs that truncate long messages.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosOpenEnfile(probability = 0.05)
 * class OpenEnfileTest {
 *   @Test
 *   void startupBacksOffWhenSystemFileDescriptorLimitIsReached() {
 *     // assert that startup retries with delay rather than aborting permanently
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosOpenEmfile
 * @see ChaosOpenEacces
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosOpenEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.OPEN, errno = Errno.ENFILE)
public @interface ChaosOpenEnfile {

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
   * @ChaosOpenEnfile(id = "primary",  probability = 0.001)
   * @ChaosOpenEnfile(id = "replica",  probability = 0.01)
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
    ChaosOpenEnfile[] value();
  }
}
