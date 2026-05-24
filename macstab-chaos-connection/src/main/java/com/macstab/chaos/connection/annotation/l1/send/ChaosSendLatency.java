/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.send;

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
 * Delays every {@code send(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making data transmission slower than the application expects
 * while still delivering the actual sent data.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SEND}, effect = LATENCY) tuple.
 * Unlike errno variants, the latency primitive always delegates to the real kernel call after the
 * configured extra delay — the data is transmitted normally. A Bernoulli trial with probability
 * {@link #toxicity} gates whether the delay fires on each call. No runtime operation-effect
 * validation is needed.
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
 *   <li>On each intercepted {@code send} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer sleeps for {@link #delayMs} ms before issuing
 *       the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>End-to-end request latency increases by the injected delay on each send call; assert that
 *       the application's write timeout is configured to accommodate the injected delay without
 *       triggering a false timeout.
 *   <li>Streaming or chunked protocols that perform many small sends per request accumulate the
 *       injected delay on every send call; assert that the protocol implementation uses buffered
 *       writes or {@code sendmsg} to minimize the number of send calls per request.
 *   <li>The delay occupies the thread during the entire sleep period before data enters the kernel
 *       send buffer; for blocking sockets this means the send call takes at least {@link #delayMs}
 *       longer than the network round-trip time. Assert that upstream timeouts account for this
 *       additional write-side cost.
 *   <li>Assert that TCP's Nagle algorithm interaction is as expected: with the delay injected
 *       before the send call, small writes that would normally be coalesced by Nagle may now be
 *       buffered for longer, potentially improving throughput at the cost of increased latency.
 * </ul>
 *
 * <p>In production, slow {@code send} calls occur when the process is CPU throttled by cgroups and
 * spends extended time waiting to be scheduled before entering the kernel's send path, when the
 * send buffer is nearly full and the caller must wait for TCP's flow control to drain it, and when
 * NUMA topology causes the process to run on a CPU distant from the NIC's memory domain, increasing
 * the cost of copying user data into kernel sk_buff structures.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The injected delay is added before the {@code send} syscall, so the total send time observed
 * by the caller is {@link #delayMs} plus the time for the kernel to copy data into the send buffer
 * and (for blocking sockets with a full buffer) the time for TCP's flow control to drain the
 * buffer. If the send buffer has space when {@code send} is called, the kernel call itself returns
 * quickly after a memcpy; the total observed latency is dominated by the injection delay.
 *
 * <p>For protocols that require multiple send calls to deliver one logical request (e.g., HTTP/1.1
 * with chunked encoding, or a protocol that sends a header and then a body as separate write
 * calls), the effective per-request latency increase is N × {@link #delayMs} where N is the number
 * of send calls per request. This helps reveal whether application-level write timeouts are
 * calibrated for the worst-case number of send calls per request rather than for a single bulk
 * write.
 *
 * <p>TCP's Nagle algorithm coalesces small sends into larger segments to reduce the number of
 * packets on the wire. With send-side latency injected, small writes are delayed before entering
 * the kernel, which gives Nagle more data to coalesce. Applications that disable Nagle via {@code
 * TCP_NODELAY} (common in low-latency RPC frameworks) will not benefit from this coalescing effect,
 * and each delayed send will result in a separate small packet on the wire.
 *
 * <p>Java's blocking {@code Socket.getOutputStream().write()} calls translate to one or more {@code
 * send} or {@code write} syscalls depending on the buffer size and the JVM implementation. Java's
 * NIO {@code SocketChannel.write()} calls translate directly to {@code write} or {@code sendmsg}
 * syscalls. Both paths are intercepted by libchaos-net when the process runs with the shared
 * library in {@code LD_PRELOAD}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSendLatency(delayMs = 150, toxicity = 0.3)
 * class SendLatencyTest {
 *   @Test
 *   void writeTimeoutFiresCorrectlyUnderSlowSend(ConnectionInfo info) {
 *     // assert that write timeout fires when send delay exceeds the configured threshold
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRecvLatency
 * @see ChaosSendEtimedout
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding
 */
@Repeatable(ChaosSendLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.SEND)
public @interface ChaosSendLatency {

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
   * @ChaosSendLatency(id = "primary",  probability = 0.001)
   * @ChaosSendLatency(id = "replica",  probability = 0.01)
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
    ChaosSendLatency[] value();
  }
}
