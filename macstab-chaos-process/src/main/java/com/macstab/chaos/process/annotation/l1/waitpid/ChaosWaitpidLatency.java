/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.waitpid;

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
 * Delays every {@code waitpid} call intercepted by libchaos-process by {@link #delayMs}
 * milliseconds before delegating to the real kernel call, causing child reaping to succeed but take
 * longer than expected, accumulating zombie processes during the delay window.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WAITPID}, effect = LATENCY) tuple.
 * Unlike errno variants, LATENCY always delegates to the real kernel call after the delay — no
 * child state is missed and no errno is injected; only wall-clock cost is added. Compile-time
 * safety: invalid selector/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code waitpid} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code waitpid} call the interposer sleeps for {@link #delayMs} milliseconds
 *       before issuing the real kernel call.</li>
 *   <li>The real {@code waitpid} call is then issued and its result (pid, status, errno) is
 *       returned unchanged to the caller — no errno is injected, no child state is lost.</li>
 *   <li>The calling code observes: correct return value and status, but the call returns
 *       {@link #delayMs} ms later than expected; children that exited during the delay remain
 *       zombies for the duration of the delay on every wait cycle.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code waitpid} returns correctly but with added latency; assert that the application's
 *       child-reaping loop completes within its allotted time budget — applications that depend on
 *       low-latency child reaping (e.g., process supervisors with heartbeat timeouts) must account
 *       for waitpid latency when calibrating their cleanup deadlines.</li>
 *   <li>Children that exit while a previous waitpid call is delayed remain zombies for up to
 *       {@link #delayMs} ms; assert that the application's zombie accumulation monitoring does not
 *       false-alert during the delay window — short-lived bursts of zombies are expected when
 *       child reaping is delayed.</li>
 *   <li>Assert that request handlers that block in waitpid (e.g., synchronous command execution)
 *       carry the full waitpid delay in their response time; any request timeout budget must
 *       include the maximum expected waitpid cost to avoid spurious timeout failures during
 *       scheduler stall events.</li>
 *   <li>Assert that the application does not assume waitpid is instantaneous when computing
 *       child cleanup timeouts; a cleanup deadline of {@code child_exit_time + cleanup_budget}
 *       must add the maximum observed waitpid latency to the budget to be correct.</li>
 * </ul>
 * Production failure mode: a request handler synchronously forks a subprocess, waits for it with
 * {@code waitpid}, and holds a database connection open during the wait; kernel scheduling stalls
 * during high system load extend waitpid latency by 50–200 ms; the connection pool timeout fires
 * before waitpid returns; the handler receives a pool-exhaustion error; the subprocess result is
 * discarded, and the handler retries; each retry spawns another subprocess, compounding load.
 *
 * <h2>Deep technical dive</h2>
 * <p>Real sources of waitpid latency that LATENCY models: kernel scheduler stalls before the
 * calling thread is woken from the wait queue after a child delivers SIGCHLD; wait-queue processing
 * overhead under high child-exit rates (many children exiting simultaneously saturate the
 * wait-queue notification path); NUMA effects where the calling thread and the child process ran
 * on different memory nodes (cross-node wait-queue wakeup is slower than local); CPU frequency
 * scaling that delays the scheduler on power-constrained hosts; and cgroup throttling that
 * suspends the calling thread's CPU slice mid-wait. All of these are correct-result-but-slow
 * failures that no errno can model.
 *
 * <p>The delay is injected before the real {@code waitpid} call, not after. This means that
 * during the delay the child has not yet been reaped even if it has already exited; the child
 * remains a zombie for the delay duration. Under LATENCY injection, the zombie window per child
 * is extended by {@link #delayMs} ms beyond the child's natural exit time. Applications that
 * monitor zombie counts via {@code /proc} must tolerate elevated zombie counts during high child
 * exit rates when waitpid latency is significant.
 *
 * <p>A key correctness concern for blocking waitpid callers: applications that call
 * {@code waitpid(pid, &status, 0)} (blocking, no WNOHANG) while holding resources (connections,
 * locks, file descriptors) expose those resources to the full waitpid delay. Under LATENCY
 * injection, this tests whether the resource hold time is bounded independently of child-reaping
 * speed. The correct pattern is to release resources before waiting, or to use a non-blocking
 * loop ({@code waitpid(pid, &status, WNOHANG)}) with a poll interval that fits within the
 * resource hold budget.
 *
 * <p>Latency is applied uniformly to every intercepted waitpid call regardless of the pid
 * argument. This means group waits ({@code waitpid(-pgid, ...)}) and any-child waits
 * ({@code waitpid(-1, ...)}) are delayed identically to specific-pid waits. Applications that
 * use a SIGCHLD handler calling {@code waitpid(-1, WNOHANG)} to drain all zombies will observe
 * each individual {@code waitpid} call delayed; the total drain time for N zombie children is
 * approximately N × {@link #delayMs} ms under full injection.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWaitpidLatency(delayMs = 150)
 * class WaitpidLatencyTest {
 *   @Test
 *   void requestHandlerDoesNotHoldConnectionAcrossWaitpidAndZombieMonitorToleratesDelayWindow(ConnectionInfo info) {
 *     // verify connections released before waitpid; zombie alert not triggered during delay window;
 *     // request timeout budget includes max waitpid cost; non-blocking poll pattern used
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–200 ms models realistic kernel scheduler stalls and NUMA
 * wakeup delays; values above the application's request timeout or cleanup deadline expose timeout
 * budget miscalibration; values above 500 ms produce zombie accumulation visible in {@code /proc}
 * monitoring; start with 50 ms to confirm the application is not latency-sensitive, then increase.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessLatencyBinding
 * @see com.macstab.chaos.process.model.ProcessRule#latency(ProcessSelector, java.time.Duration)
 */
@Repeatable(ChaosWaitpidLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessLatencyTranslator")
@ProcessLatencyBinding(selector = ProcessSelector.WAITPID)
public @interface ChaosWaitpidLatency {

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
   * @ChaosWaitpidLatency(id = "primary",  probability = 0.001)
   * @ChaosWaitpidLatency(id = "replica",  probability = 0.01)
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
    ChaosWaitpidLatency[] value();
  }
}
