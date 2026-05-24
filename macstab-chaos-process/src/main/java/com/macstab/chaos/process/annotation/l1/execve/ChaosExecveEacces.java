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
 * Injects {@code EACCES} into {@code execve} calls intercepted by libchaos-process, causing the
 * calling code to observe a permission-denied failure when attempting to replace the process image.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code EACCES}) tuple.
 * The {@code EXECVE} selector intercepts {@code execve} calls only, leaving {@code fork}, {@code
 * pthread_create}, {@code posix_spawn}, {@code posix_spawnp}, {@code execveat}, and {@code waitpid}
 * unaffected. Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.
 *   <li>On each {@code execve} call the interposer runs a Bernoulli trial with probability {@link
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
 *   <li>{@code execve} returns {@code -1}; {@code errno = EACCES} (13); the binary exists but the
 *       process does not have execute permission — the application must handle the failure without
 *       assuming the binary is absent (distinct from {@code ENOENT}).
 *   <li>Applications that invoke helper binaries or shell scripts as part of request processing
 *       (e.g. a transcoder spawning {@code ffmpeg}, a database invoking an external compressor)
 *       must handle {@code EACCES} from {@code execve} gracefully — assert that the application
 *       logs the permission failure with the attempted path and falls back to an internal
 *       implementation rather than propagating an unhandled exception.
 *   <li>Assert that the application distinguishes {@code EACCES} (permission denied — the binary
 *       exists but is not executable) from {@code ENOENT} (binary not found) in its diagnostic
 *       messages; operators require different remediation steps for each: {@code EACCES} requires a
 *       {@code chmod} or SELinux/AppArmor policy change; {@code ENOENT} requires installing the
 *       missing binary.
 * </ul>
 *
 * Production failure mode: a Kubernetes pod drops capabilities (e.g. removes the execute bit from a
 * sidecar binary via an incorrect init container) or an SELinux/AppArmor policy update denies
 * {@code execve} to the application's service account; the application's subprocess invocations
 * fail with {@code EACCES} and the application silently loses external-tool functionality without
 * surfacing a clear diagnostic.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EACCES} for {@code execve} when: (1) the calling process does not have
 * execute permission on the file; (2) the file resides on a filesystem mounted with the {@code
 * noexec} flag; (3) the kernel's security module (SELinux, AppArmor, Landlock) denies the exec
 * operation based on policy. On Linux, the kernel checks these conditions in sequence in {@code
 * do_open_execat}: permission bits first, then mount flags, then LSM hooks.
 *
 * <p>The most operationally significant source of {@code EACCES} on {@code execve} in containerised
 * environments is LSM policy: SELinux and AppArmor policies that restrict which binaries a
 * container's process domain can execute are set at container startup. A policy update that
 * restricts the allowed exec targets will cause previously-successful {@code execve} calls to fail
 * with {@code EACCES} for the container's process domain. This failure is invisible in integration
 * tests that run without the LSM policy active.
 *
 * <p>A second important source is the {@code noexec} mount option: containers that mount volumes
 * (config maps, secrets, persistent volumes) with {@code noexec} cannot execute binaries from those
 * volumes. Orchestrators that auto-apply security hardening (e.g. mounting all volumes with {@code
 * noexec,nosuid,nodev} by default in a hardened cluster) may cause unexpected {@code EACCES}
 * failures for applications that place executable scripts or helper binaries in mounted volumes.
 *
 * <p>Compared with {@code EPERM}: {@code EACCES} indicates the credentials or LSM policy for the
 * specific target file are insufficient (may be fixable with {@code chmod} or policy update);
 * {@code EPERM} indicates the operation class is denied for the process regardless of the target
 * (requires a capability or privilege change). Both are non-retryable without a system change.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEacces(probability = 0.01)
 * class ExecvePermissionTest {
 *   @Test
 *   void applicationFallsBackToInternalImplWhenExternalBinaryDenied(ConnectionInfo info) {
 *     // verify fallback to internal implementation when helper binary returns EACCES
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; permission failures are non-transient in
 * production so even low rates usefully exercise the error handling path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.EACCES)
public @interface ChaosExecveEacces {

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
   * @ChaosExecveEacces(id = "primary",  probability = 0.001)
   * @ChaosExecveEacces(id = "replica",  probability = 0.01)
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
    ChaosExecveEacces[] value();
  }
}
