/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.fork;

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
 * Delays every {@code fork} call intercepted by libchaos-process by {@link #delayMs} milliseconds
 * before delegating to the real kernel call, causing the calling code to observe a slow fork
 * without receiving an error.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code FORK}, effect = LATENCY) pair. Unlike
 * ERRNO variants, the LATENCY primitive always delegates to the real kernel call — it only injects
 * wall-clock cost before issuing the syscall. The fork succeeds (or fails for genuine reasons);
 * only the time taken increases. Compile-time safety: invalid selector/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code fork} wrapper at the dynamic-linker level.
 *   <li>On each {@code fork} call the interposer sleeps for {@link #delayMs} milliseconds in the
 *       calling thread before issuing the real syscall — the calling thread is stalled for the full
 *       delay period before the kernel creates the child process.
 *   <li>After the sleep the real {@code fork} syscall is issued; the kernel processes it normally
 *       and returns either the child pid (parent) or 0 (child) on success, or -1 on failure.
 *   <li>The calling code receives: the real kernel return value, after a wall-clock delay of at
 *       least {@link #delayMs} ms; no spurious errno is injected; the fork either succeeds or fails
 *       for a genuine kernel reason such as EAGAIN or ENOMEM.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every {@code fork} call takes at least {@link #delayMs} ms longer than baseline; assert
 *       that request timeouts that include a fork call are calibrated to include the fork latency
 *       plus the child's processing time — a fixed request timeout measured from request arrival
 *       will fire before the request is complete if the fork is delayed.
 *   <li>Applications that use fork for per-request isolation (CGI-style processes, credential
 *       isolation daemons) must account for fork latency in their SLA calculations — assert that
 *       the application's latency budget allocates headroom for slow fork calls under kernel
 *       scheduling pressure rather than assuming instantaneous fork.
 *   <li>Assert that the parent's {@code waitpid} timeout (if any) is set relative to the child's
 *       processing completion, not relative to the fork call start — a timeout measured from before
 *       the fork will fire {@link #delayMs} ms prematurely under latency injection.
 * </ul>
 *
 * Production failure mode: a CGI-style application server forks a child process to handle each HTTP
 * request; the host kernel is under scheduling pressure from co-located workloads; fork stalls for
 * 150ms in the kernel's copy-on-write page table setup; the application's request timeout is 200ms
 * measured from request arrival; the forked child has only 50ms remaining to produce a response,
 * causing intermittent timeouts that are attributed to the application rather than to the fork
 * scheduling stall.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The LATENCY primitive injects delay before the {@code fork} syscall, simulating the
 * pre-syscall scheduling stall that occurs when the calling thread is preempted or waits for a
 * scheduler slot on a CPU-saturated node. The actual fork syscall (page table duplication via
 * copy-on-write marking, credential copying, signal handler copying) runs at kernel speed after the
 * sleep. This models the scenario where the delay is in the userspace-to-kernel transition and CPU
 * scheduling, not in the kernel's fork implementation.
 *
 * <p>Fork latency is particularly significant in copy-heavy workloads because the kernel must walk
 * the parent's page table to mark all pages as copy-on-write, even under CoW. For processes with
 * large virtual address spaces (e.g. JVM workers forking for subprocess execution), the page table
 * walk alone can take tens of milliseconds. The LATENCY primitive allows testing the application's
 * response to this latency without requiring a large-memory process or a loaded kernel.
 *
 * <p>The combination of fork latency and copy-on-write page faults can cause surprising behaviour:
 * the fork itself returns quickly, but the child process's first memory access triggers CoW page
 * faults as it diverges from the parent's pages. Under memory pressure, these faults can stall the
 * child for much longer than the fork call itself. The LATENCY primitive isolates the fork call
 * latency; to model the CoW fault latency in the child, combine this annotation with memory-level
 * chaos annotations.
 *
 * <p>Processes that use fork for credential isolation (AWS EKS Pod Identity, GCP Workload Identity
 * Federation, IAM role assumption) must account for fork latency in their credential delivery
 * deadlines. If the credential delivery system sets a timeout from the token request to the process
 * launch, fork latency reduces the budget available for the actual credential handshake. Assert
 * that the credential delivery timeout is set relative to child process readiness, not relative to
 * the fork call.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosForkLatency(delayMs = 100)
 * class ForkSchedulingStallTest {
 *   @Test
 *   void requestTimeoutIncludesForkLatencyAndDoesNotFirePrematurely(ConnectionInfo info) {
 *     // verify fork completes despite 100ms delay; request timeout not prematurely fired;
 *     // child has full processing budget after fork completes
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 10–100ms simulates realistic scheduling stalls and CoW page
 * table walk costs for moderate-sized processes; values above the application's request timeout
 * expose timeout calibration gaps; 200ms+ simulates heavily loaded nodes with many context
 * switches.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessLatencyBinding
 * @see com.macstab.chaos.process.model.ProcessRule#latency(ProcessSelector, java.time.Duration)
 */
@Repeatable(ChaosForkLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessLatencyTranslator")
@ProcessLatencyBinding(selector = ProcessSelector.FORK)
public @interface ChaosForkLatency {

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
   * @ChaosForkLatency(id = "primary",  probability = 0.001)
   * @ChaosForkLatency(id = "replica",  probability = 0.01)
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
    ChaosForkLatency[] value();
  }
}
