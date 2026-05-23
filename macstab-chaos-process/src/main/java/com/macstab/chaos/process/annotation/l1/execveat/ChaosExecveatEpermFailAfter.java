/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execveat;

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
 * After {@link #successesBeforeFailure} successful {@code execveat} calls, injects {@code EPERM}
 * on every subsequent call, causing the calling code to observe an operation-not-permitted failure
 * that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code EPERM}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed, then the
 * counter trips permanently and every subsequent call fails until the rule is removed. This is
 * distinct from ERRNO (independent Bernoulli trial on each call) and LATENCY (unconditional delay).
 * Compile-time safety: invalid selector/errno/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter. Each {@code execveat} call that
 *       passes the counter check decrements the remaining budget; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code execveat} call
 *       sets {@code errno = EPERM} and returns {@code -1} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 1,
 *       {@code strerror}: "Operation not permitted"; the calling process remains unchanged and
 *       the {@code dirfd} must be closed explicitly since no close-on-exec processing occurs for
 *       failed execs; retrying without a privilege change will not succeed after the trip.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code -1} with {@code errno = EPERM}; assert that the application recognises
 *       {@code EPERM} as a non-transient, non-retryable error that requires operator intervention
 *       rather than backoff-and-retry (since the process's capability class is now denied).</li>
 *   <li>Container runtimes using {@code execveat} must close the open {@code dirfd} on each
 *       {@code EPERM} failure — assert that each failure closes the dirfd before propagating the
 *       error, since failed execs do not trigger {@code FD_CLOEXEC} processing on the dirfd.</li>
 *   <li>Assert that the application distinguishes {@code EPERM} from {@code EACCES}: EPERM (1)
 *       means the operation class is denied for this process ({@code PR_SET_NO_NEW_PRIVS} set,
 *       seccomp filter blocking {@code execveat} syscall 322, or capability check failure);
 *       EACCES (13) means file credentials or LSM policy denied exec — EPERM requires a capability
 *       configuration change while EACCES may be fixable with file permissions or policy update.</li>
 * </ul>
 * Production failure mode: a Kubernetes security context includes
 * {@code allowPrivilegeEscalation: false}, which sets {@code PR_SET_NO_NEW_PRIVS} on the container
 * process; the container runtime uses {@code execveat} to launch a setuid helper binary; the first
 * N launches succeed because the binary is not setuid at that point; a deployment replaces the
 * binary with a setuid version; all subsequent launches return {@code EPERM}; the runtime leaks
 * the dirfd on each failure and surfaces a generic exec error rather than identifying the
 * no-new-privs + setuid conflict for the operator.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER is the appropriate model for security hardening events that occur mid-lifecycle.
 * Real {@code EPERM} from {@code execveat} is sticky: once {@code PR_SET_NO_NEW_PRIVS} is set
 * (via the Kubernetes security context), it cannot be cleared — every subsequent exec of a
 * setuid binary in the same process tree will fail with {@code EPERM}. Setting
 * {@link #successesBeforeFailure} to the number of execs before the security policy change
 * takes effect models this state-machine transition exactly.
 *
 * <p>The seccomp filter case is equally sticky under FAIL_AFTER: if a seccomp profile is loaded
 * that blocks the {@code execveat} syscall (number 322 on x86-64) and the filter is applied after
 * the container has already made N successful exec calls (e.g. via a dynamic seccomp profile
 * loader that activates on a runtime event), all subsequent calls return {@code EPERM}. The
 * {@code execveat}-specific syscall number means some seccomp profiles block it while allowing
 * {@code execve} — applications that assume {@code execveat} is always available because
 * {@code execve} was allowed will encounter {@code EPERM} only when the filter is applied.
 *
 * <p>The {@code PR_SET_NO_NEW_PRIVS} bit is inherited across {@code fork} and {@code execve}:
 * once set in a process, all descendants share the restriction permanently. FAIL_AFTER with a
 * threshold corresponding to the fork+exec count before the bit is set tests whether the
 * application's process management layer correctly handles the permanent permission change
 * in a realistic scenario — N successes then permanent denial.
 *
 * <p>Unlike ENOMEM, FAIL_AFTER for EPERM should not trigger a backoff-and-retry strategy:
 * {@code EPERM} is a capability-class denial that persists regardless of timing. Applications
 * that treat {@code EPERM} from exec as a transient error and retry with backoff will enter
 * an infinite retry loop — the test must verify that the application recognises {@code EPERM}
 * as permanent and escalates to operator alerting rather than in-process recovery.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEpermFailAfter(successesBeforeFailure = 5)
 * class ExecveatSecurityHardeningTest {
 *   @Test
 *   void runtimeCloseDirfdAndAlertsOperatorOnEpermAfterSecurityPolicyChange(ConnectionInfo info) {
 *     // first 5 execveat calls succeed; subsequent calls return EPERM;
 *     // verify dirfd closed on each failure; EPERM escalated to operator alert; no retry loop
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of successful execs before the security policy change takes effect; values in the
 * range 1–20 cover most security hardening scenarios; 0 means the capability denial is in
 * effect from the first exec (seccomp filter applied at container start).
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveatEpermFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.EPERM)
public @interface ChaosExecveatEpermFailAfter {

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
   * @ChaosExecveatEpermFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveatEpermFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveatEpermFailAfter[] value();
  }
}
