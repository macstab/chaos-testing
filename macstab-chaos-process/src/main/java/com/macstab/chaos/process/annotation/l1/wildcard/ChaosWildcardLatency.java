/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

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
 * Delays every process-management syscall intercepted by libchaos-process — {@code fork}, {@code
 * execve}, {@code posix_spawn}, {@code pthread_create}, {@code waitpid}, and their variants — by
 * {@link #delayMs} milliseconds before delegating to the real kernel call, causing the entire
 * process lifecycle to succeed but with uniformly added latency across all operations.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, effect = LATENCY) tuple. Unlike
 * errno variants, LATENCY always delegates to the real kernel call after the delay — no child state
 * is missed and no errno is injected; only wall-clock cost is added uniformly across all
 * process-management syscall families. Compile-time safety: invalid selector/effect combinations
 * have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.
 *   <li>On each intercepted syscall (any of the wildcard family: fork, execve, execveat,
 *       posix_spawn, posix_spawnp, pthread_create, waitpid), the interposer sleeps for {@link
 *       #delayMs} milliseconds before issuing the real kernel call.
 *   <li>The real kernel call is then issued and its result (return value, status, errno) is
 *       returned unchanged to the caller — no errno is injected, no state is lost.
 *   <li>The calling code observes: correct return value, but every process-management call returns
 *       {@link #delayMs} ms later than expected; combined, all process lifecycle phases take
 *       significantly longer than baseline.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every process-management call succeeds but with added latency; assert that the
 *       application's request timeout budget accounts for the maximum expected latency from every
 *       process-management call it makes per request — applications that assume fork and thread
 *       creation are instantaneous underestimate their request latency under load.
 *   <li>Child reaping (waitpid) is delayed by {@link #delayMs} ms per call; assert that zombie
 *       accumulation monitoring does not false-alert during the delay window — children that exit
 *       while a previous waitpid is delayed remain zombies for the delay duration.
 *   <li>Thread creation (pthread_create) is delayed by {@link #delayMs} ms; assert that thread pool
 *       warmup time budgets account for this delay — a pool that pre-creates N threads requires at
 *       least N × {@link #delayMs} ms to initialise under full WILDCARD latency.
 *   <li>Assert that the application's process-lifecycle sequencing (spawn → wait for ready signal →
 *       serve first request) has a total timeout budget that covers the sum of delays across all
 *       process-management calls in the sequence, not just each call in isolation.
 * </ul>
 *
 * Production failure mode: a request handler forks a subprocess, waits for its readiness signal via
 * a pipe, then serves the request; under kernel scheduling pressure, fork adds 30 ms and waitpid
 * adds 50 ms; the combined latency crosses the request handler's 75 ms timeout; the handler times
 * out, kills the subprocess, and the request fails — not because any individual operation was slow,
 * but because their combined latency was not budgeted.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The WILDCARD selector delays all process-management syscalls simultaneously. This is the
 * highest-blast-radius LATENCY variant — it models the scenario where the kernel's scheduler is
 * globally stressed rather than only one subsystem (e.g., only the clone path or only the wait
 * queue). Real sources of cross-family process management latency: CPU frequency scaling that
 * affects all kernel paths equally; cgroup CPU throttling that suspends the calling thread
 * regardless of which syscall it is in; NUMA effects where the thread runs on a node far from its
 * memory; and kernel preemption latency that adds to every blocking syscall.
 *
 * <p>The delay is applied before each real kernel call. Cumulative effects compound quickly: a
 * request that forks (delay), waits for readiness (waitpid with delay), serves the request
 * (possibly creating threads with delay), and reaps the subprocess (waitpid with delay) incurs at
 * least 4 × {@link #delayMs} of added latency even for a single, simple request. The WILDCARD
 * variant surfaces this compounding effect — single-selector variants only test one stage of the
 * pipeline.
 *
 * <p>Thread pool initialisation is particularly affected: creating N threads at startup requires N
 * × {@link #delayMs} ms of additional init time under full wildcard latency. Applications that set
 * a fixed init timeout (e.g., "fail if pool not ready in 5 s") must set the timeout to {@code N ×
 * max_thread_create_latency + safety_margin}. The wildcard latency variant exposes incorrect fixed
 * init timeouts that were calibrated only for fast-startup conditions.
 *
 * <p>Process lifecycle sequences with multiple consecutive process-management calls are most
 * affected: spawn + waitpid (synchronous command execution), fork + exec + waitpid (shell command
 * execution), pthread_create + thread-join (synchronous work delegation). Each step in the sequence
 * incurs the full delay, and request timeout budgets must be calibrated against the total sequence
 * cost, not the individual step cost.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardLatency(delayMs = 50)
 * class ProcessLifecycleLatencyTest {
 *   @Test
 *   void requestTimeoutBudgetCoversAllProcessManagementCallsInLifecycleSequence(ConnectionInfo info) {
 *     // drive requests that trigger fork, thread create, and waitpid;
 *     // verify request timeout budget accounts for sum of all process-management delays;
 *     // verify pool warmup timeout covers N × delayMs; verify zombie monitor tolerates delay window
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–200 ms models realistic kernel scheduler stalls and CPU
 * throttling effects across all process-management families; values above 100 ms will extend
 * application startup significantly if the startup sequence involves many process and thread
 * creation calls — start with 20 ms to confirm overall lifecycle correctness, then increase to find
 * the timeout budget ceiling.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessLatencyBinding
 * @see com.macstab.chaos.process.model.ProcessRule#latency(ProcessSelector, java.time.Duration)
 */
@Repeatable(ChaosWildcardLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessLatencyTranslator")
@ProcessLatencyBinding(selector = ProcessSelector.WILDCARD)
public @interface ChaosWildcardLatency {

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
   * @ChaosWildcardLatency(id = "primary",  probability = 0.001)
   * @ChaosWildcardLatency(id = "replica",  probability = 0.01)
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
    ChaosWildcardLatency[] value();
  }
}
