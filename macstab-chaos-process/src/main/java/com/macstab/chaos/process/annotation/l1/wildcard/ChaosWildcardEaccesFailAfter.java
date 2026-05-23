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
 * all intercepted families, injects {@code EACCES} on every subsequent call, modelling a MAC
 * security policy tightening scenario where a new SELinux/AppArmor profile blocks all
 * process-management operations after N successful ones.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EACCES},
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
 *       with {@code errno = EACCES}.</li>
 *   <li>The calling code receives: {@code fork()}/{@code execve()} return {@code -1} with
 *       {@code errno = EACCES} (13); {@code posix_spawn}/{@code pthread_create} return
 *       {@code EACCES} directly; {@code strerror(EACCES)}: "Permission denied".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return EACCES permanently; assert that the application treats EACCES as
 *       a non-retryable security policy failure and escalates to operators rather than retrying
 *       — MAC policy denials are permanent for the lifetime of the security context.</li>
 *   <li>FAIL_AFTER models a MAC policy tightening event: N process-management calls succeed under
 *       the old policy; a new SELinux/AppArmor profile is applied; all subsequent calls return
 *       EACCES — assert that the application detects the onset of EACCES and sends a security
 *       policy change alert rather than treating it as a transient error.</li>
 *   <li>Assert that the application's generic process-management error handler correctly
 *       propagates EACCES from all call sites — not just the specific one that was tested in
 *       isolation — since the wildcard fires EACCES across all families simultaneously.</li>
 * </ul>
 * Production failure mode: a Kubernetes operator applies a new SELinux policy that denies fork
 * and clone for a running container; the application starts receiving EACCES from all process
 * and thread creation attempts; it treats EACCES as EAGAIN and applies a retry loop with back-off;
 * the retry loop consumes CPU and the security alert is never sent; operators are not notified
 * that the security policy changed until the container stops serving requests.
 *
 * <h2>Deep technical dive</h2>
 * <p>EACCES from process-management syscalls arises exclusively from MAC policy enforcement
 * (SELinux type enforcement, AppArmor confinement, TOMOYO path rules). Unlike EPERM which can
 * sometimes be resolved by dropping capabilities, EACCES from MAC cannot be resolved by the
 * application — it requires a security policy change by an operator. The FAIL_AFTER variant
 * models the hot policy change scenario: N calls succeed before the policy is applied, then
 * all subsequent calls fail permanently until the container is restarted with a compatible policy.
 *
 * <p>The WILDCARD FAIL_AFTER counter is shared across all intercepted syscall families. The
 * EACCES phase begins as soon as the counter is exhausted by the combined call traffic from
 * all families. For sequenced testing, set {@link #successesBeforeFailure} to the expected total
 * call count across all families during the pre-restriction phase.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. The
 * first test method exercises the pre-restriction phase (N calls succeed); subsequent test methods
 * exercise the EACCES-restriction phase where all process management is blocked by policy.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEaccesFailAfter(successesBeforeFailure = 30)
 * class MacPolicyTighteningTest {
 *   @Test
 *   void applicationEscalatesToOperatorsOnEaccesOnsetAndDoesNotRetry(ConnectionInfo info) {
 *     // first 30 process calls succeed; subsequent calls return EACCES;
 *     // verify EACCES not treated as EAGAIN; verify security alert sent; verify no retry loop;
 *     // verify health check returns DEGRADED rather than HEALTHY
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the total number
 * of process-management calls the application makes before the policy tightening scenario occurs;
 * values 10–200 cover typical init + steady-state phases; 0 means the policy is applied before
 * startup.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEaccesFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EACCES)
public @interface ChaosWildcardEaccesFailAfter {

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
   * @ChaosWildcardEaccesFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEaccesFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEaccesFailAfter[] value();
  }
}
