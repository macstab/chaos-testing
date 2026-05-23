/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execve;

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
 * Injects {@code EPERM} into {@code execve} calls intercepted by libchaos-process, causing the
 * calling code to observe an operation-not-permitted failure when attempting to replace the process
 * image.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code EPERM}) tuple.
 * The {@code EXECVE} selector intercepts {@code execve} calls only, leaving {@code fork},
 * {@code pthread_create}, {@code posix_spawn}, {@code posix_spawnp}, {@code execveat}, and
 * {@code waitpid} unaffected. Compile-time safety: invalid selector/errno combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code execve} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EPERM} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 1,
 *       {@code strerror}: "Operation not permitted"; no new process image is loaded.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code execve} returns {@code -1}; {@code errno = EPERM} (1); the operation class is
 *       structurally denied for this process — the process does not have the required capability
 *       to execute the target binary, and retrying without a privilege change will not succeed.</li>
 *   <li>Applications that use {@code execve} to invoke setuid or setgid binaries must handle
 *       {@code EPERM} when running in a capability-dropped container (e.g. with
 *       {@code securityContext.allowPrivilegeEscalation: false}) — assert that the application
 *       detects the privilege escalation failure and reports it as a configuration error rather
 *       than an unrecoverable crash.</li>
 *   <li>Assert that the application distinguishes {@code EPERM} (capability/privilege denial —
 *       requires a policy change) from {@code EACCES} (file permission/LSM denial — may be
 *       fixable with {@code chmod} or a targeted policy update); both prevent the exec from
 *       succeeding, but the remediation paths are entirely different.</li>
 * </ul>
 * Production failure mode: a container deployed to a hardened Kubernetes cluster with
 * {@code allowPrivilegeEscalation: false} and {@code seccompProfile: RuntimeDefault} attempts to
 * execute a setuid helper binary as part of its request processing; the kernel returns
 * {@code EPERM} because the seccomp profile blocks the privilege escalation; the application
 * crashes with an unhandled exception rather than gracefully disabling the privileged feature.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EPERM} for {@code execve} when the process does not have permission
 * to execute the file due to its privilege level relative to the file's set-user-ID or
 * set-group-ID bit. On Linux, the specific case is: the file has the set-user-ID or set-group-ID
 * bit set, and the calling process is in a namespace without the required capability ({@code
 * CAP_SETUID} or {@code CAP_SETGID}) to perform privilege escalation. The kernel's
 * {@code do_execve_common} path checks capabilities before allowing the privilege elevation.
 *
 * <p>The most common production source of {@code EPERM} from {@code execve} is Kubernetes
 * security context hardening: pods with {@code allowPrivilegeEscalation: false} have the
 * {@code PR_SET_NO_NEW_PRIVS} bit set (via {@code prctl}), which prevents any exec from
 * gaining privileges. Any attempt to execute a setuid binary returns {@code EPERM} regardless
 * of the file's permission bits. This is a correct security control but breaks applications
 * that rely on setuid helpers (e.g. {@code sudo}, {@code newgrp}, system utilities that
 * require elevated privileges for specific operations).
 *
 * <p>A second source is seccomp profile enforcement: a seccomp filter that blocks the
 * {@code execve} syscall entirely returns {@code EPERM} (via the seccomp action
 * {@code SECCOMP_RET_ERRNO} with errno 1). Applications running in environments with restrictive
 * seccomp profiles (OpenShift's default restricted SCC, GKE's Container-Optimized OS default
 * profile) may encounter {@code EPERM} for exec calls that are blocked by the profile.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEperm(probability = 0.01)
 * class ExecvePrivilegeEscalationTest {
 *   @Test
 *   void applicationDisablesPrivilegedFeatureWhenExecveReturnsEperm(ConnectionInfo info) {
 *     // verify application detects EPERM, logs configuration error, continues without the feature
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; privilege escalation failures are
 * non-transient so any non-zero probability exercises the error path; avoid 1.0 to prevent
 * breaking container-init exec calls.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.EPERM)
public @interface ChaosExecveEperm {

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
   * @ChaosExecveEperm(id = "primary",  probability = 0.001)
   * @ChaosExecveEperm(id = "replica",  probability = 0.01)
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
    ChaosExecveEperm[] value();
  }
}
