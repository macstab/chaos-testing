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
 * Injects {@code EPERM} into {@code execveat} calls intercepted by libchaos-process, causing the
 * calling code to observe an operation-not-permitted failure when attempting to replace the process
 * image relative to a directory file descriptor.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code EPERM}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = EPERM} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 1, {@code strerror}: "Operation
 *       not permitted"; no new process image is loaded and the calling process remains unchanged.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code execveat} returns {@code -1}; {@code errno = EPERM} (1); the operation class is
 *       structurally denied for this process — the process cannot execute the target binary
 *       regardless of file permissions, and retrying without a privilege change will not succeed.
 *   <li>Container runtimes using {@code execveat} with {@code AT_EMPTY_PATH} must handle {@code
 *       EPERM} when the process has {@code PR_SET_NO_NEW_PRIVS} set (via {@code
 *       allowPrivilegeEscalation: false}) and the binary fd is a setuid binary — assert that the
 *       runtime closes the {@code dirfd}, logs the no-new-privs context, and surfaces a
 *       capability-configuration error rather than a generic exec failure.
 *   <li>Assert that the application distinguishes {@code EPERM} (process class denied, requires
 *       capability change) from {@code EACCES} (file permission or LSM policy denied, may be
 *       fixable with {@code chmod} or policy update) — the operator runbook actions differ.
 * </ul>
 *
 * Production failure mode: a container runtime opens the entrypoint binary fd and calls {@code
 * execveat(dirfd, "", argv, envp, AT_EMPTY_PATH)}; the Kubernetes security context includes {@code
 * allowPrivilegeEscalation: false}, setting {@code PR_SET_NO_NEW_PRIVS}; if the binary is setuid,
 * the exec fails with {@code EPERM}; the runtime must detect this case and surface a clear "setuid
 * binary not permitted with allowPrivilegeEscalation:false" error rather than a generic exec
 * failure that leaves operators without a clear remediation.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code execveat} inherits all of {@code execve}'s privilege-checking semantics — the {@code
 * EPERM} sources are identical: {@code PR_SET_NO_NEW_PRIVS} blocking setuid exec, seccomp filter
 * blocking the {@code execveat} syscall (syscall number 322 on x86-64), and capability checks for
 * operations that require elevated privileges. The {@code AT_EMPTY_PATH} pattern has one additional
 * nuance: the LSM hook ({@code security_bprm_check}) is applied to the already-open file
 * description, and the check may differ from what a fresh {@code execve} path lookup would trigger
 * if the file's security label has been updated after it was opened.
 *
 * <p>The seccomp filter case is particularly significant: some restrictive profiles block {@code
 * execveat} specifically while allowing {@code execve} (or vice versa). Applications that use
 * {@code execveat} for security benefits (TOCTOU resistance, sandbox isolation) must verify that
 * their seccomp profile permits the syscall; an {@code EPERM} from a seccomp filter on {@code
 * execveat} may be silent (no audit log entry) and require kernel tracing to diagnose.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEperm(probability = 0.01)
 * class ExecveatPrivilegeTest {
 *   @Test
 *   void runtimeReportsNoNewPrivsContextOnEpermAndClosesDirfd(ConnectionInfo info) {
 *     // verify dirfd closed on EPERM; no-new-privs context in error message
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; privilege escalation failures are
 * non-transient; any non-zero probability exercises the error handling path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveatEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.EPERM)
public @interface ChaosExecveatEperm {

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
   * @ChaosExecveatEperm(id = "primary",  probability = 0.001)
   * @ChaosExecveatEperm(id = "replica",  probability = 0.01)
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
    ChaosExecveatEperm[] value();
  }
}
