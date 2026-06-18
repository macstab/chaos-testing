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
 * EBUSY} on every subsequent call, modelling a sustained NPTL stack-cache contention scenario where
 * the cache lock is perpetually held by a stalled reclamation operation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code PTHREAD_CREATE}, errno = {@code EBUSY},
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
 *       call returns {@code EBUSY} directly (pthread_create returns the error code, not -1).
 *   <li>The calling code receives: return value {@code EBUSY} (16); no thread is created; the NPTL
 *       stack cache lock is modelled as permanently held.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code EBUSY}; assert that the application checks the return value (pthread_create
 *       returns the error code directly, not -1; it does not set {@code errno}) and does not spin
 *       on EBUSY without a sleep — sustained EBUSY requires a yield before retry.
 *   <li>FAIL_AFTER models a sustained cache-lock stall (e.g. a thread in the process of freeing a
 *       stack is preempted and cannot release the cache lock): N creates succeed while the lock is
 *       available; subsequent creates all return EBUSY — assert that the application detects this
 *       sustained failure and escalates rather than retrying indefinitely.
 *   <li>Assert that the application distinguishes sustained EBUSY (cache-lock stall, may warrant
 *       alert if persisting beyond a few hundred milliseconds) from transient EBUSY
 *       (sub-millisecond contention, self-resolving with a brief yield).
 * </ul>
 *
 * Production failure mode: a high-throughput server's thread pool uses FAIL_AFTER to model the
 * scenario where the NPTL stack cache enters a deadlocked state; N threads are created normally;
 * all subsequent creates return EBUSY permanently; the pool does not detect the sustained failure
 * and retries in a tight loop consuming 100% CPU without creating any new threads.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models a sustained NPTL stack-cache lock contention. In transient EBUSY scenarios
 * (the normal ERRNO variant), the cache lock is briefly held and releases in microseconds.
 * FAIL_AFTER models the degenerate case: a thread holding the cache lock is preempted for an
 * extended period (e.g. swapped out under memory pressure); pthread_create continues to see EBUSY
 * on every attempt; the only recovery is for the preempted thread to be scheduled and release the
 * lock.
 *
 * <p>pthread_create returns the error code directly — checking {@code if (ret == -1)} or {@code if
 * (errno == EBUSY)} after pthread_create silently misses EBUSY (16). Code that tests {@code if (ret
 * != 0)} is correct. The counter does not reset between test methods when the annotation is at
 * class scope, enabling sequential testing of the normal growth phase (calls 1 through N) and the
 * sustained-EBUSY phase without restarting the container.
 *
 * <p>EBUSY from pthread_create is a glibc/NPTL-specific extension not listed in the POSIX
 * specification. Applications that handle only POSIX-documented errors (EAGAIN, EINVAL, EPERM) from
 * pthread_create will silently swallow EBUSY, treating it as an unrecognised errno and potentially
 * converting it to a generic "thread creation failed" without diagnostic detail. FAIL_AFTER forces
 * the sustained-EBUSY path to be exercised in a repeatable, deterministic way.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateEbusyFailAfter(successesBeforeFailure = 8)
 * class PthreadCreateStackCacheSustainedContentionTest {
 *   @Test
 *   void threadPoolDetectsSustainedEbusyAndAlertsRatherThanSpinning(ConnectionInfo info) {
 *     // first 8 creates succeed; subsequent creates return EBUSY indefinitely;
 *     // verify no spin loop; brief yield on each EBUSY retry; alert after sustained EBUSY;
 *     // return value checked (not errno); EBUSY distinguished from EAGAIN
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the thread pool's
 * initial thread count; values 4–32 cover most realistic pool sizes; 0 means the stack cache is
 * locked from the first thread creation attempt.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPthreadCreateEbusyFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.PTHREAD_CREATE, errno = ProcessErrno.EBUSY)
public @interface ChaosPthreadCreateEbusyFailAfter {

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
   * @ChaosPthreadCreateEbusyFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPthreadCreateEbusyFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPthreadCreateEbusyFailAfter[] value();
  }
}
