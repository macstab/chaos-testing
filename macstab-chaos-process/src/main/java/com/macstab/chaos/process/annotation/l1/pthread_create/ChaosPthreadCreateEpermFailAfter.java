/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.pthread_create;

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
 * After {@link #successesBeforeFailure} successful {@code pthread_create} calls, injects {@code
 * EPERM} on every subsequent call, modelling a security policy tightening that removes the
 * real-time scheduling privilege mid-runtime after N threads have been created.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code PTHREAD_CREATE}, errno = {@code EPERM},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code pthread_create} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code pthread_create}
 *       call returns {@code EPERM} directly (pthread_create returns the error code, not -1).
 *   <li>The calling code receives: return value {@code EPERM} (1); no thread is created; the
 *       process no longer has the privilege ({@code CAP_SYS_NICE}) required for real-time
 *       scheduling threads.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally with the real-time
 *       scheduling policy; all subsequent calls return {@code EPERM}; assert that the application
 *       detects the privilege change and falls back to SCHED_OTHER rather than retrying with the
 *       same privileged attribute.
 *   <li>FAIL_AFTER models a Kubernetes operator applying a more restrictive pod security policy
 *       mid-lifetime that drops {@code CAP_SYS_NICE}: N threads are created under the original
 *       policy; the policy tightens; subsequent creates return EPERM — assert that the application
 *       detects this transition and emits an alert identifying the dropped capability.
 *   <li>Assert that the application does not retry pthread_create-EPERM with the same real-time
 *       attribute; EPERM is non-retryable with the same attribute; assert that the application
 *       falls back to SCHED_OTHER and logs the scheduling degradation at WARN level.
 * </ul>
 *
 * Production failure mode: a latency-sensitive component creates real-time (SCHED_FIFO) I/O
 * threads; a Kubernetes operator tightens the pod security policy and removes CAP_SYS_NICE; N
 * existing threads continue running (the policy change does not kill existing threads); subsequent
 * thread creates return EPERM; the component does not detect the capability change and applies the
 * same tight retry loop used for EAGAIN, consuming CPU while failing to create new threads.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models a security policy tightening scenario: N thread creates succeed while
 * CAP_SYS_NICE is in the effective capability set; a Kubernetes operator applies a new
 * PodSecurityPolicy or SecurityContext that removes the capability; subsequent creates with a
 * real-time scheduling attribute return EPERM. Real EPERM from this source is permanent until the
 * security policy is reverted or the attribute is changed to SCHED_OTHER. pthread_create returns
 * the error code directly — checking {@code if (ret == -1)} silently misses EPERM (1).
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises the normal operation phase (N creates
 * with real-time scheduling); subsequent test methods exercise the EPERM-with-fallback phase. Set
 * {@link #successesBeforeFailure} to the number of thread creates expected before the security
 * policy tightens.
 *
 * <p>An important subtlety: EPERM from pthread_create fires only when the thread attribute requests
 * a real-time scheduling policy AND the process lacks CAP_SYS_NICE. If the attribute uses
 * SCHED_OTHER (the default), pthread_create never returns EPERM regardless of capability state.
 * Applications that dynamically select between real-time and best-effort scheduling based on
 * runtime detection of capabilities (via {@code prctl(PR_GET_DUMPABLE)} or similar) can avoid EPERM
 * by probing the capability at startup and configuring the attribute accordingly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateEpermFailAfter(successesBeforeFailure = 4)
 * class PthreadCreatePrivilegeTighteningTest {
 *   @Test
 *   void ioThreadFallsBackToSchedOtherOnEpermAfterPolicyChangeAndLogsCapabilityDrop(ConnectionInfo info) {
 *     // first 4 creates succeed with SCHED_FIFO; subsequent creates return EPERM;
 *     // verify fallback to SCHED_OTHER applied; CAP_SYS_NICE loss logged at WARN;
 *     // no retry with real-time attribute; return value checked (not errno)
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * real-time threads the application creates before the security policy changes; values 2–32 cover
 * most I/O thread pool sizes; 0 means no real-time threads can be created from startup.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPthreadCreateEpermFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.PTHREAD_CREATE, errno = ProcessErrno.EPERM)
public @interface ChaosPthreadCreateEpermFailAfter {

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
   * @ChaosPthreadCreateEpermFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPthreadCreateEpermFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPthreadCreateEpermFailAfter[] value();
  }
}
