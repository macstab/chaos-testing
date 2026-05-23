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
 * all intercepted families, injects {@code EBUSY} on every subsequent call, modelling a sustained
 * NPTL stack-cache lock stall scenario where a preempted thread holds the lock indefinitely after
 * N successful thread creations, causing all subsequent process-management operations to report
 * "Device or resource busy".
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EBUSY},
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
 *       with {@code errno = EBUSY}.</li>
 *   <li>The calling code receives: {@code fork()} returns {@code -1} with {@code errno = EBUSY}
 *       (16); {@code pthread_create} returns {@code EBUSY} directly; {@code strerror(EBUSY)}:
 *       "Device or resource busy".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return EBUSY permanently; assert that the application detects the
 *       sustained EBUSY condition (as opposed to transient EBUSY from the ERRNO variant) and
 *       escalates with an alert rather than retrying indefinitely — sustained EBUSY indicates a
 *       preempted lock holder, not a momentary contention spike.</li>
 *   <li>FAIL_AFTER models the sustained stall scenario: N process-management calls succeed; a
 *       thread holding the NPTL stack-cache lock is preempted (e.g., by a CPU scheduler change
 *       or priority inversion); all subsequent calls return EBUSY — assert that the application
 *       bounds its EBUSY retry count and escalates after the retry budget is exhausted.</li>
 *   <li>Assert that the application distinguishes sustained EBUSY (FAIL_AFTER, no recovery) from
 *       transient EBUSY (ERRNO, resolves quickly): the retry strategy for sustained EBUSY must
 *       escalate rather than continue backing off and retrying indefinitely.</li>
 * </ul>
 * Production failure mode: a high-throughput thread pool hits the NPTL stack-cache lock under
 * heavy concurrency; a low-priority thread holding the lock is preempted by the OS scheduler
 * while holding the lock; all concurrent pthread_create calls return EBUSY; the pool's retry
 * logic applies exponential back-off without a maximum retry count; the pool stops expanding
 * and starts dropping requests while consuming scheduler cycles in the back-off loop.
 *
 * <h2>Deep technical dive</h2>
 * <p>EBUSY from pthread_create is glibc/NPTL-specific (not in POSIX spec, not produced by musl).
 * The NPTL stack-cache uses an internal lock to protect its free-list; transient contention
 * produces brief EBUSY (sub-millisecond, resolves when the lock is released). The FAIL_AFTER
 * variant models the extreme case: the lock holder is preempted indefinitely, causing all
 * subsequent pthread_create calls to return EBUSY until the preempted thread is rescheduled.
 * This distinguishes the "brief yield and retry" scenario (transient) from the "alert operators"
 * scenario (sustained).
 *
 * <p>The WILDCARD FAIL_AFTER counter is shared across all process-management families. Once the
 * EBUSY phase begins, all process-management operations return EBUSY — not just pthread_create.
 * This tests whether the application's cross-family error handling escalates correctly when
 * EBUSY affects the entire process lifecycle, not just thread creation.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. First
 * test method: N successful calls (normal operation). Subsequent test methods: EBUSY phase (all
 * process management blocked). Set {@link #successesBeforeFailure} to the total process-management
 * call count during the pre-stall phase.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEbusyFailAfter(successesBeforeFailure = 50)
 * class NptlStackCacheStallTest {
 *   @Test
 *   void threadPoolAlertsOperatorsOnSustainedEbusyAfterRetryBudgetExhausted(ConnectionInfo info) {
 *     // first 50 process calls succeed; subsequent calls return EBUSY permanently;
 *     // verify bounded retry with escalation; verify alert sent; verify no indefinite back-off;
 *     // verify sustained vs transient EBUSY distinction in escalation logic
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * process-management calls the application makes before the stall scenario; values 10–200 cover
 * typical workload phases; 0 means EBUSY fires from the very first call (blocks startup).
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEbusyFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EBUSY)
public @interface ChaosWildcardEbusyFailAfter {

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
   * @ChaosWildcardEbusyFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEbusyFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEbusyFailAfter[] value();
  }
}
