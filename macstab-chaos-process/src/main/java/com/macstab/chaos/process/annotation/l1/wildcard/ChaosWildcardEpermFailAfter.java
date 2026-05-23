/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

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
 * After {@link #successesBeforeFailure} successful process-management syscall invocations across
 * all intercepted families, injects {@code EPERM} on every subsequent call, modelling a Kubernetes
 * security context tightening scenario where a pod security policy drops capabilities mid-runtime,
 * causing all subsequent process-management operations to report "Operation not permitted".
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EPERM},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N intercepted
 * process-management calls (across all families — fork, execve, posix_spawn, pthread_create,
 * waitpid) succeed, then the counter trips permanently and every subsequent call returns the error
 * code until the rule is removed. Compile-time safety: invalid selector/errno/effect combinations
 * have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter shared across all intercepted syscall
 *       families; the counter does not reset automatically between test methods when the annotation
 *       is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent process-management
 *       call returns {@code -1} (or the errno value directly for pthread_create and posix_spawn)
 *       with {@code errno = EPERM}.</li>
 *   <li>The calling code receives: {@code fork()}/{@code execve()} return {@code -1} with
 *       {@code errno = EPERM} (1); {@code pthread_create} returns {@code EPERM} directly;
 *       {@code strerror(EPERM)}: "Operation not permitted".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return EPERM permanently; assert that the application detects the EPERM
 *       onset as a security policy change event and escalates to operators — EPERM from process
 *       management is non-retryable; no amount of capability management by the application will
 *       restore the removed capability.</li>
 *   <li>FAIL_AFTER models the capability revocation scenario: N process-management calls succeed
 *       while CAP_SYS_NICE and related capabilities are held; a Kubernetes operator applies a
 *       new pod security policy that drops these capabilities; all subsequent thread and process
 *       creation calls return EPERM — assert that the application detects the capability change
 *       and alerts operators rather than spinning in a retry loop.</li>
 *   <li>Assert that EPERM from pthread_create triggers the SCHED_OTHER fallback (if the attribute
 *       requested real-time scheduling) rather than treating it as a fatal error — the thread can
 *       still be created without the elevated scheduling policy; only if the fallback also returns
 *       EPERM should the application escalate.</li>
 * </ul>
 * Production failure mode: a Kubernetes operator deploys a new pod security policy that drops
 * CAP_SYS_NICE from containers already running; all thread creation attempts using real-time
 * scheduling start returning EPERM; the application treats EPERM from pthread_create as a fatal
 * error instead of falling back to SCHED_OTHER; the thread pool exhausts; the container stops
 * serving requests while the operator is unaware that the security context changed.
 *
 * <h2>Deep technical dive</h2>
 * <p>EPERM from process-management syscalls indicates a DAC capability check failure: the process
 * attempted an operation that requires a Linux capability (CAP_SYS_NICE for real-time scheduling,
 * CAP_SYS_ADMIN for some clone flags) that has been revoked. Unlike EACCES (MAC policy from
 * SELinux/AppArmor), EPERM arises from the kernel's capability table check — more fundamental
 * and faster to check, but equally permanent for the lifetime of the process.
 *
 * <p>The WILDCARD counter charges across all process-management families. The EPERM phase begins
 * when the combined traffic exhausts the counter. Set {@link #successesBeforeFailure} to the
 * expected total process-management call count during the pre-revocation phase.
 *
 * <p>The counter does not reset between test methods at class scope. First test method: N
 * successful calls (capabilities held). Subsequent test methods: EPERM phase (capabilities
 * dropped). The fallback path for EPERM from pthread_create (retry with SCHED_OTHER) must be
 * tested in the subsequent test method to verify the degraded-mode behavior is correct.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEpermFailAfter(successesBeforeFailure = 45)
 * class CapabilityRevocationTest {
 *   @Test
 *   void threadPoolFallsBackToSchedOtherAndAlertsOperatorsOnEpermOnset(ConnectionInfo info) {
 *     // first 45 process calls succeed; subsequent calls return EPERM;
 *     // verify SCHED_OTHER fallback attempted before escalation; verify operator alert sent;
 *     // verify EPERM vs EACCES classified correctly; verify no infinite retry loop
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the total
 * process-management call count during normal operation before the capability revocation event;
 * values 10–200 cover typical workload phases; 0 means capabilities are absent from startup.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEpermFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EPERM)
public @interface ChaosWildcardEpermFailAfter {

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
   * @ChaosWildcardEpermFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEpermFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEpermFailAfter[] value();
  }
}
