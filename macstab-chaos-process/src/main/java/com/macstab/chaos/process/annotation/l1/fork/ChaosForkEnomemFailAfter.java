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
 * After {@link #successesBeforeFailure} successful {@code fork} calls, injects {@code ENOMEM} on
 * every subsequent call, causing the calling code to observe an out-of-memory failure that persists
 * for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code FORK}, errno = {@code ENOMEM}, effect =
 * FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed, then the
 * counter trips permanently and every subsequent call fails until the rule is removed. This is
 * distinct from ERRNO (independent Bernoulli trial on each call) and LATENCY (unconditional delay).
 * Compile-time safety: invalid selector/errno/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code fork} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter. Each {@code fork} call that passes the
 *       counter check decrements the remaining budget; the counter does not reset automatically
 *       between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code fork} call sets
 *       {@code errno = ENOMEM} and returns {@code -1} without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 12, {@code strerror}: "Out of
 *       memory"; no child process is created; the calling process is in a clean state since no
 *       child resources were allocated before the interposer fired.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code -1} with {@code errno = ENOMEM}; assert that the application surfaces a
 *       memory-pressure alert and applies backoff — ENOMEM from fork may persist for the duration
 *       of a node memory pressure event, requiring longer backoff than the transient EAGAIN case.
 *   <li>FAIL_AFTER is more accurate than probabilistic ERRNO for modelling the progressive kernel
 *       memory exhaustion pattern: the node's slab allocator has capacity for the first N forks
 *       (each consuming kernel stack, task_struct, mm_struct); the (N+1)th fork tips the allocator
 *       into exhaustion; all subsequent forks fail — assert that the application detects this
 *       threshold crossing and escalates to a platform alert.
 *   <li>Assert that the application does not leak resources in its fork-failure path: since no
 *       child was allocated, there is no zombie to reap and no dirfd to close — the only
 *       requirement is that the caller receives a clean error and that pre-fork allocations
 *       (argument preparation, context setup) are freed in the failure path.
 * </ul>
 *
 * Production failure mode: a sandboxing service forks a child for each incoming request to provide
 * process-boundary isolation; over time the node's kernel slab memory is consumed by other
 * workloads; after N successful forks the slab allocator cannot satisfy the next request; the
 * service enters a state where no new sandboxes can be created; without a platform alert the
 * operations team does not know whether the service is degraded or just idle.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the kernel slab exhaustion curve more accurately than probabilistic ERRNO.
 * Real ENOMEM from fork follows a threshold: the slab allocator's free lists drain as the system
 * creates more processes; once the kmalloc pool for task_struct-sized objects is empty, fork fails
 * for every caller regardless of their own memory usage. Setting {@link #successesBeforeFailure} to
 * the observed number of concurrent processes at the exhaustion point reproduces this threshold.
 *
 * <p>The ENOMEM semantics for fork have an important property: since no child was allocated, the
 * parent's state is exactly the same as it was before the fork call. This is in contrast to
 * exec-ENOMEM, where the calling process may have changed state in preparation for the exec (e.g.
 * opened a dirfd). For fork, the only cleanup required is to release any memory or file descriptors
 * that the application allocated in the pre-fork setup phase (e.g. argument vectors, environment
 * copies). Applications that defer error handling assume fork is atomic with respect to their
 * pre-fork setup may leak these resources if the cleanup path is not explicit.
 *
 * <p>Under FAIL_AFTER, the application must implement a state machine for its fork-based worker: in
 * the success phase (first N forks) it behaves normally; in the failure phase it must apply
 * load-shedding or queuing for all incoming requests until the memory condition clears. The test
 * verifies this state machine by checking that the application's behaviour changes at the
 * threshold.
 *
 * <p>The counter does not reset between test methods at class scope. This allows a test class to
 * verify the success phase in early methods and the ENOMEM-with-alerting phase in later methods,
 * mirroring the production incident timeline where the memory condition develops over minutes or
 * hours and the failure is not immediately obvious without metrics.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosForkEnomemFailAfter(successesBeforeFailure = 50)
 * class ForkKernelMemoryExhaustionTest {
 *   @Test
 *   void serviceAlertsAndShedsLoadAfterForkEnomemThreshold(ConnectionInfo info) {
 *     // first 50 fork calls succeed; subsequent calls return ENOMEM;
 *     // verify memory-pressure alert raised; load-shedding activated; no silent request drop
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of concurrent child processes before the node's kernel slab memory is exhausted; values in
 * the range 20–200 cover most sandboxing and process-isolation scenarios; 0 means kernel memory is
 * exhausted before the first fork (tests cold-start failure handling).
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosForkEnomemFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.FORK, errno = ProcessErrno.ENOMEM)
public @interface ChaosForkEnomemFailAfter {

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
   * @ChaosForkEnomemFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosForkEnomemFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosForkEnomemFailAfter[] value();
  }
}
