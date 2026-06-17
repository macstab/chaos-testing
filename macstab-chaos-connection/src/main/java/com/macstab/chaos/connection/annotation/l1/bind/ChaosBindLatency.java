/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.bind;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding;
import com.macstab.chaos.connection.annotation.l1.listen.ChaosListenLatency;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Delays every {@code bind(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making the address assignment slower than the application
 * expects while still producing a successful bind result.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code BIND}, effect = LATENCY) tuple.
 * Unlike errno variants, the latency primitive always delegates to the real kernel call after the
 * configured extra delay — the bind succeeds and the socket is assigned the requested local
 * address. A Bernoulli trial with probability {@link #toxicity} gates whether the delay fires on
 * each call. No runtime operation-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.NET)} on the container definition causes the
 *       extension to upload {@code libchaos-net.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code connect}, {@code accept}, {@code socket}, {@code
 *       bind}, {@code listen}, {@code shutdown}, {@code send}, {@code recv}, and {@code poll} at
 *       the dynamic-linker level.
 *   <li>On each intercepted {@code bind} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer sleeps for {@link #delayMs} ms before issuing
 *       the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Server startup takes longer when bind is delayed; assert that health-check or
 *       readiness-probe timeouts are generous enough to accommodate slow bind calls during startup
 *       under load.
 *   <li>Applications that pipeline socket setup (socket → bind → listen → accept) in a sequence
 *       will see the delay propagate into the total startup time; assert that startup timeouts are
 *       configured to accommodate this latency.
 *   <li>Services that set up multiple listening sockets sequentially (e.g., binding to both HTTP
 *       and HTTPS ports) will accumulate per-bind delays; assert that the total startup time budget
 *       accounts for the number of sockets being bound.
 *   <li>Assert that the delay does not affect the established connections already being served —
 *       bind latency injected during a restart-in-place scenario should not cause in-flight
 *       requests to timeout.
 * </ul>
 *
 * <p>In production, slow {@code bind} calls occur when the kernel's routing subsystem is under load
 * (e.g., route cache invalidations from BGP convergence), when cgroup memory limits cause kernel
 * slab allocations to stall, or when the system is recovering from a previous memory pressure event
 * and reclaiming pages.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code bind(2)} is typically sub-millisecond because it performs only local kernel operations
 * (hash table insertion, route lookup for the local address). The delay injected by this annotation
 * is therefore larger than real-world bind latency in the vast majority of cases; its value is to
 * expose startup timeout assumptions that are invisible when bind completes instantly.
 *
 * <p>Container orchestration readiness probes measure the time from container start until the first
 * successful health-check response. If bind latency exceeds the probe's {@code initialDelaySeconds}
 * minus the time for the JVM to start and load application classes, the probe fires before the
 * service is ready to accept connections. This triggers a container restart loop. This injection
 * reveals the margin between the actual startup time and the readiness probe timeout without
 * requiring a production incident.
 *
 * <p>The delay fires before the kernel call, so the bind still succeeds after the delay and no
 * socket resource is leaked. This distinguishes the latency injection from error injections, which
 * leave the socket unbound and require the application to close it and create a new one.
 *
 * <p>When combined with {@link ChaosListenLatency}, the two injections simulate the full socket
 * setup phase being slow: bind assigns the local address and listen creates the accept queue; both
 * must complete before the service can accept connections. Injecting delay into both operations
 * reveals whether the startup timeout covers the complete socket initialisation sequence.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosBindLatency(delayMs = 200, toxicity = 0.5)
 * class BindLatencyTest {
 *   @Test
 *   void readinessProbeToleratesSlowBindDuringStartup(ConnectionInfo info) {
 *     // assert that readiness probe succeeds despite slow bind calls
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosBindEaddrinuse
 * @see ChaosListenLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding
 */
@Repeatable(ChaosBindLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.BIND)
public @interface ChaosBindLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 100L;

  /**
   * @return probability the latency fires when matched, in {@code (0.0, 1.0]}
   */
  double toxicity() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-net
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosBindLatency(id = "primary",  probability = 0.001)
   * @ChaosBindLatency(id = "replica",  probability = 0.01)
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
    ChaosBindLatency[] value();
  }
}
