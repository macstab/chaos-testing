/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execveat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessErrnoBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Injects {@code EACCES} into {@code execveat} calls intercepted by libchaos-process, causing the
 * calling code to observe a permission-denied failure when attempting to replace the process image
 * relative to a directory file descriptor.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code EACCES}) tuple.
 * The {@code EXECVEAT} selector intercepts {@code execveat} calls only (the Linux-specific
 * directory-relative exec syscall), leaving {@code execve} and all other process syscalls
 * unaffected. Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.
 *   <li>On each {@code execveat} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = EACCES} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 13, {@code strerror}:
 *       "Permission denied"; no new process image is loaded.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code execveat} returns {@code -1}; {@code errno = EACCES} (13); the file referenced by
 *       {@code dirfd} + {@code pathname} (or the fd itself with {@code AT_EMPTY_PATH}) does not
 *       have execute permission for the calling process — assert that the application handles the
 *       permission failure and closes the {@code dirfd} to avoid a file descriptor leak.
 *   <li>Container runtimes using {@code AT_EMPTY_PATH} (exec-by-fd) to launch the container
 *       entrypoint must handle {@code EACCES} when the binary's execute permission bits are not set
 *       or an LSM policy denies exec on the open fd — assert that the runtime surfaces the
 *       permission error with the fd number and the binary path if available.
 *   <li>Assert that the application distinguishes {@code EACCES} on {@code execveat} from {@code
 *       EACCES} on file open operations — an exec permission failure has different remediation
 *       (chmod, LSM policy) than an open permission failure (file ownership).
 * </ul>
 *
 * Production failure mode: a container runtime opens the entrypoint binary before applying an
 * SELinux context change, then attempts {@code execveat} with {@code AT_EMPTY_PATH} after the
 * context change makes the fd non-executable under the new policy; the exec fails with {@code
 * EACCES} even though the binary file is executable — because the LSM check is on the fd's current
 * security context, not the file's inode permission bits.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code execveat} with {@code AT_EMPTY_PATH} uses the open file description referenced by
 * {@code dirfd} directly as the executable. The kernel's LSM hook ({@code security_bprm_check}) is
 * applied to the already-open file description, not to a fresh path lookup. This means that an
 * SELinux or AppArmor policy change that takes effect after the file is opened but before the exec
 * is issued can cause {@code EACCES} on the exec without modifying the file's permission bits — a
 * subtle TOCTOU-style failure that is invisible in path-based permission checks.
 *
 * <p>The {@code noexec} mount flag is checked against the mount point of the {@code dirfd}'s
 * filesystem, not the relative path's mount point. When using {@code execveat} with a relative
 * path, the binary's mount must not have {@code noexec}; when using {@code AT_EMPTY_PATH}, the
 * mount of the open fd must not have {@code noexec}. Applications that use {@code execveat} for
 * sandbox execution (running code from a memory-backed or tmpfs directory) must verify that the
 * source directory is not mounted {@code noexec}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEacces(probability = 0.01)
 * class ExecveatPermissionTest {
 *   @Test
 *   void runtimeSurfacesEaccesWithFdContextAndClosesDirfd(ConnectionInfo info) {
 *     // verify dirfd closed on EACCES; LSM vs chmod distinction in error message
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; permission failures are non-transient in
 * production, so any non-zero probability exercises the error handling path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveatEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.EACCES)
public @interface ChaosExecveatEacces {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-process
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosExecveatEacces(id = "primary",  probability = 0.001)
   * @ChaosExecveatEacces(id = "replica",  probability = 0.01)
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
    ChaosExecveatEacces[] value();
  }
}
