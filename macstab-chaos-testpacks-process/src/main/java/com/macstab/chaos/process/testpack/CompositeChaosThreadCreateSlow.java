/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Every {@code pthread_create()} call is delayed by the configured latency before completing,
 * simulating slow thread initialisation. Applications that create threads on the hot path —
 * cached-thread-pool expansion, reactive worker allocation, or JDBC connection-pool growth — will
 * observe increased response-time variance because threads that should be ready in microseconds
 * take hundreds of milliseconds to start.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.latency(ProcessSelector.PTHREAD_CREATE, latencyMs)} via
 * libchaos-process. In production slow {@code pthread_create()} arises on heavily loaded NUMA
 * systems where stack allocation for new threads must cross memory-bus boundaries, on systems with
 * overloaded cgroups memory controllers adding latency to kernel allocations, or on VMs whose host
 * is under memory pressure causing balloon-driver interventions during the new thread's stack
 * allocation.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Thread-creation latency does not cause failures directly — only delays. The impact surfaces when
 * thread creation occurs on the request-handling path: each request that triggers pool expansion is
 * delayed by {@code latencyMs}. Services that front-load thread creation during startup warm-up
 * avoid the latency on steady-state requests. Services without warm-up or with dynamic pool sizing
 * will surface intermittent latency spikes to users.
 *
 * <h2>Industry references</h2>
 *
 * <p>NUMA-related {@code pthread_create()} latency is documented in the Linux {@code numactl(8)}
 * man-page and in the NUMA-aware application-development guide from Red Hat. JVM thread-creation
 * latency under GC pressure is discussed in the OpenJDK JVM Tuning Guide (GC overhead during stack
 * allocation). Netflix's Hystrix and Resilience4j documentation both cite thread-creation latency
 * as a factor in bulkhead sizing decisions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosThreadCreateSlow(latencyMs = 200)
 * class ThreadCreateSlowTest {
 *   @Test
 *   void requestLatencyStaysWithinSlaUnderThreadPoolExpansion() {
 *     // assert: P99 response time within SLA budget; no timeout during pool expansion
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosThreadCreateSlow.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.ThreadCreateSlowComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosThreadCreateSlow {

  /**
   * Delay added to each {@code pthread_create()} call in milliseconds. Defaults to {@code 200} (200
   * ms — typical NUMA cross-node allocation penalty under load).
   */
  long latencyMs() default 200L;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosThreadCreateSlow[] value();
  }
}
