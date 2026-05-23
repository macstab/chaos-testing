/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.fork;

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
 * After {@link #successesBeforeFailure} successful {@code fork} calls, injects {@code EAGAIN} on
 * every subsequent call, causing the calling code to observe a resource-temporarily-unavailable
 * failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code FORK}, errno = {@code EAGAIN}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed, then
 * the counter trips permanently and every subsequent call fails until the rule is removed. This
 * is distinct from ERRNO (independent Bernoulli trial on each call) and LATENCY (unconditional
 * delay). Compile-time safety: invalid selector/errno/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code fork} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter. Each {@code fork} call that passes
 *       the counter check decrements the remaining budget; the counter does not reset automatically
 *       between test methods when the annotation is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code fork} call
 *       sets {@code errno = EAGAIN} and returns {@code -1} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 11,
 *       {@code strerror}: "Resource temporarily unavailable"; no child process is created; the
 *       calling process continues in its current state.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code -1} with {@code errno = EAGAIN}; assert that the application implements
 *       a bounded retry loop with backoff rather than an infinite retry or a hard failure, since
 *       EAGAIN from fork is a transient resource condition in production.</li>
 *   <li>FAIL_AFTER is more accurate than probabilistic ERRNO for modelling the {@code RLIMIT_NPROC}
 *       exhaustion pattern: the first N forks succeed as the uid's process count rises; the N+1th
 *       fork hits the limit and fails with EAGAIN; all subsequent forks continue to fail until
 *       child processes exit — assert that the application's process pool has an upper bound
 *       that is set below {@code RLIMIT_NPROC} to avoid hitting the limit in production.</li>
 *   <li>Assert that the application does not treat post-threshold EAGAIN as a permanent failure
 *       requiring operator escalation — EAGAIN from fork is self-healing once child processes
 *       exit and the uid's process count falls below {@code RLIMIT_NPROC}.</li>
 * </ul>
 * Production failure mode: a process-isolation service forks a child for each incoming request;
 * the request rate exceeds the rate at which children exit; the uid's process count approaches
 * {@code RLIMIT_NPROC}; after N successful forks, all subsequent forks return EAGAIN; the service
 * has no upper bound on its process pool and no retry logic, so all requests during the exhaustion
 * window fail and the service enters a degraded state that persists until the process count falls.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER models the {@code RLIMIT_NPROC} exhaustion curve more accurately than probabilistic
 * ERRNO. Real fork-EAGAIN from {@code RLIMIT_NPROC} follows a deterministic threshold: the uid's
 * process count rises with each fork that has not yet been waited on; once it reaches the limit,
 * all subsequent forks return EAGAIN until children are reaped. Setting
 * {@link #successesBeforeFailure} to the uid's {@code RLIMIT_NPROC} minus the baseline process
 * count reproduces this threshold exactly.
 *
 * <p>The EAGAIN semantics for fork are particularly important for daemon processes that use
 * fork-per-request for isolation. Under the probabilistic ERRNO variant, each fork has an
 * independent probability p of failing — this models transient kernel pressure where failures
 * are scattered across the request stream. Under FAIL_AFTER, the first N forks succeed and then
 * all subsequent forks fail until the rule is removed — this models the resource-ceiling scenario
 * where the pid table fills deterministically as the service handles its Nth request. The two
 * patterns require different application responses: transient failures call for retry; ceiling
 * failures call for load-shedding and backpressure.
 *
 * <p>The counter scope matches the container lifetime, not the test method. When the annotation
 * is at class scope, a test class can execute multiple test methods: early methods exercise the
 * success phase (N forks succeed, demonstrating correct happy-path behaviour) and later methods
 * exercise the failure phase (all forks return EAGAIN, testing the load-shedding logic). This
 * requires careful ordering of test methods if the success/failure distinction matters.
 *
 * <p>Contrast with {@code ChaosForkEnomem} (ERRNO, ENOMEM): fork-ENOMEM indicates the kernel
 * cannot allocate memory for the child process structures (task_struct, mm_struct, kernel stack),
 * which is a different resource class from pid-table exhaustion. Applications should implement
 * different retry strategies for each: EAGAIN is always self-healing (wait for children to exit);
 * ENOMEM may persist longer under node-level memory pressure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosForkEagainFailAfter(successesBeforeFailure = 64)
 * class ForkProcessLimitExhaustionTest {
 *   @Test
 *   void serviceAppliesBackpressureWhenForkEagainAfterNSuccesses(ConnectionInfo info) {
 *     // first 64 fork calls succeed; subsequent calls return EAGAIN;
 *     // verify backpressure applied; retry bounded; no hard failure escalation
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of concurrent child processes before the uid's {@code RLIMIT_NPROC} is reached; values
 * in the range 20–200 cover most process-isolation service scenarios; 0 means the pid table is
 * full from the first fork attempt.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosForkEagainFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.FORK, errno = ProcessErrno.EAGAIN)
public @interface ChaosForkEagainFailAfter {

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
   * @ChaosForkEagainFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosForkEagainFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosForkEagainFailAfter[] value();
  }
}
