/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.pthread_create;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessLatencyBinding;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Adds a fixed delay of {@link #delayMs} milliseconds before each {@code pthread_create} call
 * intercepted by libchaos-process, causing thread creation to succeed but take longer than
 * expected, exercising timeout budgets and startup latency assumptions in code that creates threads
 * on the critical path.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code PTHREAD_CREATE}, effect = {@code LATENCY})
 * tuple. Unlike errno annotations, the latency primitive always delegates to the real kernel after
 * the delay — no thread creation is suppressed, no errno is injected. Compile-time safety: invalid
 * selector/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code pthread_create} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code pthread_create} call the interposer sleeps for {@link #delayMs} milliseconds
 *       before issuing the real kernel call.</li>
 *   <li>The real {@code pthread_create} executes normally after the delay: thread stack is allocated,
 *       the new thread is scheduled, and the thread function begins executing.</li>
 *   <li>The calling code receives a return value of 0 (success) and a valid thread id, but the
 *       elapsed wall-clock time is increased by {@link #delayMs} ms on every create call.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>Each {@code pthread_create} call succeeds but takes at least {@link #delayMs} ms; assert
 *       that the application's thread-creation timeout budget accounts for kernel scheduling stall
 *       cost — applications that set thread-creation timeouts based on nominal workloads will fail
 *       under realistic memory and scheduler pressure.</li>
 *   <li>Applications that create threads on the critical path (e.g. request-handler thread creation
 *       in the hot path of a server) accumulate latency: N creates × {@link #delayMs} ms; assert
 *       that request latency SLOs account for thread-creation cost, not only for the handler
 *       execution time after the thread starts.</li>
 *   <li>Assert that the application measures thread readiness from the moment the thread starts
 *       executing (when the thread function is invoked), not from the moment pthread_create is
 *       called — the delay occurs before the kernel fork, so the thread does not start executing
 *       until after the full delay elapses.</li>
 * </ul>
 * Production failure mode: a thread-per-connection server creates a new handler thread for each
 * accepted connection; under high load the kernel scheduler stalls thread stack allocation by
 * tens of milliseconds; connection handling latency increases by the create stall on every
 * request; the latency budget is calibrated only against handler execution time and does not
 * account for the create stall, causing SLO breaches that cannot be traced to slow handlers
 * because the handlers themselves complete on time.
 *
 * <h2>Deep technical dive</h2>
 * <p>pthread_create latency in production originates from three sources this annotation models in
 * aggregate: (1) NPTL stack allocation — when the stack cache is empty, glibc calls {@code mmap}
 * to allocate a new stack; under memory pressure, the page fault handling and CoW setup for the
 * new stack can take tens of milliseconds; (2) kernel task_struct and kernel stack allocation —
 * the clone syscall allocates kernel data structures from the slab allocator; under slab memory
 * pressure this adds latency; (3) scheduler registration — the kernel adds the new thread to the
 * run queue; under high thread counts and real-time priority inversion, this registration can stall.
 *
 * <p>The delay fires on every {@code pthread_create} call, not at a configurable probability. This
 * makes latency injection deterministic and simplifies timeout budget calculations: if N threads
 * are created during pool startup, the total startup latency includes N × {@link #delayMs} ms
 * from create stalls alone. Thread pools with eager thread creation (all threads at startup) are
 * more sensitive to create latency than lazy pools (threads created on demand).
 *
 * <p>Unlike errno injection, the latency primitive allows the thread to start and run normally.
 * Tests using this annotation can assert on both the latency overhead (pthread_create call duration)
 * and on correct thread behaviour (function execution, shared state access, condition variable
 * waits). This is particularly useful for testing that the application does not hold locks across
 * pthread_create calls — holding a lock during a delayed create causes all other threads waiting
 * on that lock to stall for {@link #delayMs} ms.
 *
 * <p>Thread pool implementations that create replacement threads synchronously (on a thread exit
 * callback while holding the pool lock) are especially sensitive to pthread_create latency:
 * the pool lock is held for the duration of the create plus the delay, blocking all pool operations
 * during this window. Applications should create replacement threads asynchronously or without
 * holding the pool lock.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateLatency(delayMs = 100)
 * class PthreadCreateSchedulingStallTest {
 *   @Test
 *   void requestHandlerLatencySloAccountsForThreadCreateStallNotOnlyHandlerExecution(ConnectionInfo info) {
 *     // verify SLO measured from connection accept, not thread start;
 *     // no lock held across create; pool startup latency = N * delayMs;
 *     // thread readiness measured from thread start, not from create call
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 50–200 ms simulates realistic kernel stack allocation stall
 * under memory pressure; values larger than the application's thread-creation timeout will cause
 * all creates to appear timed out, which may be intentional for testing timeout escalation paths.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessLatencyBinding
 * @see com.macstab.chaos.process.model.ProcessRule#latency(ProcessSelector, java.time.Duration)
 */
@Repeatable(ChaosPthreadCreateLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessLatencyTranslator")
@ProcessLatencyBinding(selector = ProcessSelector.PTHREAD_CREATE)
public @interface ChaosPthreadCreateLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 100L;

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
   * @ChaosPthreadCreateLatency(id = "primary",  probability = 0.001)
   * @ChaosPthreadCreateLatency(id = "replica",  probability = 0.01)
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
    ChaosPthreadCreateLatency[] value();
  }
}
