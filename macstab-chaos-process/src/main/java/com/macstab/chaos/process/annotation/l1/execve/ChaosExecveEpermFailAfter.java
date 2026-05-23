/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execve;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessFailAfterBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Allows the first {@link #successesBeforeFailure} {@code execve} calls to succeed, then injects
 * {@code EPERM} on every subsequent call, simulating a capability or seccomp policy change that
 * denies exec operations after a bounded number of successful process image replacements.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code EPERM},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is libchaos-process's counter-gated effect: the first
 * {@link #successesBeforeFailure} matched calls succeed normally; every call after that returns
 * {@code -1} with {@code errno = EPERM} until the rule is removed. This models scenarios where
 * a security policy tightening (seccomp profile update, capability drop via {@code prctl},
 * no-new-privs enforcement) takes effect after the container has already successfully executed
 * some privileged binaries.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule atomic counter of successful {@code execve} calls.</li>
 *   <li>Once the counter reaches {@link #successesBeforeFailure}, it trips and every subsequent
 *       intercepted call sets {@code errno = EPERM} and returns {@code -1} without executing.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 1,
 *       {@code strerror}: "Operation not permitted"; the counter remains tripped until removal.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} {@code execve} calls succeed; subsequent calls
 *       fail with {@code EPERM} — the application must detect the privilege-revocation transition
 *       and disable all features that require privileged exec rather than retrying indefinitely.</li>
 *   <li>Process managers that invoke setuid helpers or setcap binaries must handle mid-run
 *       privilege revocation: assert that the manager correctly transitions to a degraded state
 *       when the helper binary starts returning {@code EPERM}, ceases to queue new spawn
 *       requests that will fail, and surfaces a clear capability-denied alert.</li>
 *   <li>Assert that the application does not cache a "privileged exec is permitted" assumption
 *       from the early successful calls — each exec attempt must check the return value and
 *       respond to {@code EPERM} even if previous exec calls to the same binary succeeded.</li>
 * </ul>
 * Production failure mode: a Kubernetes operator applies a stricter security context mid-run
 * (e.g. patching the pod's security policy to set {@code allowPrivilegeEscalation: false} via
 * a MutatingWebhook); the running container receives the new {@code prctl(PR_SET_NO_NEW_PRIVS)}
 * setting on its next exec call; any attempt to execute a setuid binary after this point returns
 * {@code EPERM}; the application continues accepting requests that require the privileged helper
 * but silently drops the helper invocation step.
 *
 * <h2>Deep technical dive</h2>
 * <p>The FAIL_AFTER effect for exec-EPERM captures the policy-hardening mid-run scenario where
 * a security constraint is applied to a running container after it has already successfully
 * completed some privileged exec calls. This is distinct from the probabilistic ERRNO variant
 * (which fires randomly, simulating intermittent policy enforcement) and from a startup-time
 * configuration error (which the zero-threshold variant captures).
 *
 * <p>The POSIX {@code PR_SET_NO_NEW_PRIVS} prctl bit, once set, is inherited across fork and
 * exec and cannot be cleared. This makes the FAIL_AFTER model accurate: once the no-new-privs
 * bit is set, all subsequent exec calls that would escalate privileges fail with {@code EPERM} —
 * exactly the permanent-failure-after-N-successes pattern that FAIL_AFTER encodes.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEpermFailAfter(successesBeforeFailure = 2)
 * class PrivilegeRevocationTest {
 *   @Test
 *   void applicationDisablesPrivilegedFeatureWhenExecEpermTriggers(ConnectionInfo info) {
 *     // verify privileged feature disabled cleanly; no retry storm; capability alert raised
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * privileged exec calls that occur during the initial setup phase before the policy change takes
 * effect; zero is useful for testing startup-time privilege validation.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveEpermFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.EPERM)
public @interface ChaosExecveEpermFailAfter {

  /**
   * @return number of matched calls allowed to succeed before failure begins ({@code >= 0})
   */
  long successesBeforeFailure() default 0L;

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
   * @ChaosExecveEpermFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveEpermFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveEpermFailAfter[] value();
  }
}
