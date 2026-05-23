/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.connect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Delays every {@code connect(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making connection establishment slower than the application
 * expects while still producing a successfully connected socket.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code CONNECT}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call after
 * the configured extra delay — the connection succeeds and the socket is fully established. A
 * Bernoulli trial with probability {@link #toxicity} gates whether the delay fires on each call.
 * No runtime operation-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.NET)} on the container definition causes the
 *       extension to upload {@code libchaos-net.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code connect}, {@code accept}, {@code socket},
 *       {@code bind}, {@code listen}, {@code shutdown}, {@code send}, {@code recv}, and
 *       {@code poll} at the dynamic-linker level.
 *   <li>On each intercepted {@code connect} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer sleeps for {@link #delayMs} ms before issuing
 *       the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Connection pool acquisition time increases when connections are established slowly; assert
 *       that the pool's acquisition timeout is large enough to accommodate the injected delay plus
 *       normal network round-trip time, or that the pool correctly reports a timeout to the caller.
 *   <li>Connection pool warm-up at startup creates multiple connections simultaneously; slow connect
 *       calls extend the warm-up period and may cause the first requests to queue in the pool while
 *       connections are being established.
 *   <li>Request latency increases for requests that trigger a connection establishment (cache miss
 *       on the pool, new connection needed); assert that the request's SLA accounts for the worst-case
 *       connection establishment time.
 *   <li>Assert that the connection establishment latency is separately observable in metrics
 *       (e.g., as a pool "acquisition time" histogram) so that operators can distinguish slow
 *       connection creation from slow request execution.
 * </ul>
 *
 * <p>In production, slow {@code connect} calls occur during high-load periods when the kernel's
 * SYN retransmission timer fires (indicating packet loss on the path), when NIC interrupt
 * affinity causes the connecting thread to be scheduled on a CPU that is not co-located with the
 * NIC's interrupt handler, and during TCP window scaling negotiation with middleboxes that reset
 * connections and require reconnection without window scaling.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>A blocking {@code connect} completes in approximately one network round-trip time (RTT) under
 * normal conditions. The delay is injected before the kernel call, so the actual network RTT is
 * added to the injected delay — the total connect time observed by the application is
 * {@link #delayMs} plus the real network RTT. For tests that need to simulate slow connect without
 * network overhead, this injection accurately represents the additional latency an application
 * experiences.
 *
 * <p>Non-blocking {@code connect} (used by Netty, Vert.x, and other event-driven frameworks) calls
 * {@code connect} and then waits for the socket to become writable via {@code epoll_wait} or
 * {@code select}. The injected delay occurs before the kernel call, so the socket does not become
 * writable until the delay plus the RTT have elapsed. Event loops that set a short connect timeout
 * via the {@code SO_TIMEOUT} socket option will fire the timeout during the injected delay without
 * the kernel call ever being issued.
 *
 * <p>The delay is injected before the kernel call, so the connected socket is fully valid after the
 * delay. This is the principal behavioural difference from {@link ChaosConnectEtimedout}: a timed-out
 * connect produces no connected socket and the error must be handled immediately, while this
 * latency injection produces a successfully connected socket after a longer wait.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosConnectLatency(delayMs = 500, toxicity = 0.1)
 * class ConnectLatencyTest {
 *   @Test
 *   void connectionPoolAcquisitionTimeoutIsConfiguredCorrectly(ConnectionInfo info) {
 *     // assert pool acquisition succeeds within configuredTimeout despite slow connect
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosConnectEtimedout
 * @see ChaosConnectEconnrefused
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding
 */
@Repeatable(ChaosConnectLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.CONNECT)
public @interface ChaosConnectLatency {

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
   * @ChaosConnectLatency(id = "primary",  probability = 0.001)
   * @ChaosConnectLatency(id = "replica",  probability = 0.01)
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
    ChaosConnectLatency[] value();
  }
}
