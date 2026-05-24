/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.recv;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Corrupts bytes in the buffer returned by each intercepted {@code recv(2)} call, flipping random
 * bits in the received data at a per-byte probability of {@link #rate}, simulating bit errors
 * introduced by hardware or network corruption between the sender and the application.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RECV}, effect = CORRUPTION)
 * tuple. The injection operates at two granularities: a Bernoulli trial with probability {@link
 * #toxicity} gates whether any corruption is applied to the current {@code recv} call; when it
 * fires, each byte in the returned buffer is independently subjected to a bit-flip with probability
 * {@link #rate}. The corruption occurs after the real kernel call has returned successfully — the
 * byte count is not changed, only the content.
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
 *   <li>On each intercepted {@code recv} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires, each byte in the returned buffer is independently flipped with
 *       probability {@link #rate} before being returned to the caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Protocol parsers that perform checksum or CRC validation must detect corruption and either
 *       request retransmission (for request-response protocols) or close the connection (for
 *       streaming protocols); assert that the parser does not silently accept malformed messages.
 *   <li>Protocols with length-prefixed frames (Redis RESP, HTTP/2, gRPC) will misparse frame
 *       boundaries when length fields are corrupted; assert that the parser detects the
 *       out-of-bounds condition and closes the connection rather than reading into adjacent memory.
 *   <li>TLS-encrypted channels perform HMAC verification on each record; corruption within a TLS
 *       record will be detected by the TLS layer and result in a {@code bad_record_mac} alert
 *       rather than being passed to the application; assert that the TLS alert is handled
 *       gracefully and that reconnection is attempted.
 *   <li>Assert that the application's corruption detection metric or error counter fires and that
 *       repeated corruption triggers a circuit-breaker or connection replacement.
 * </ul>
 *
 * <p>In production, byte-level corruption occurs on faulty network hardware (NIC buffers, switch
 * fabrics, cable connections) and on storage devices used for network packet buffering. While TCP's
 * 16-bit checksum catches most corruption, it has a non-trivial false-negative rate for multi-bit
 * errors; this injection simulates corruption that passes TCP's checksum and reaches the
 * application.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>TCP provides a 16-bit one's complement checksum over the pseudo-header, TCP header, and data.
 * The probability that a burst of bit errors passes TCP's checksum undetected is approximately
 * 1/65536 for random independent errors; structured errors (e.g., swapped bytes, inverted words)
 * can pass with much higher probability. This injection simulates corruption that has already
 * passed the TCP checksum, representing the subset of real-world corruption events that TCP does
 * not catch.
 *
 * <p>Application-layer framing protocols are the primary defence: Redis RESP uses {@code \r\n}
 * delimiters and integer length prefixes that are likely to be corrupted by byte flips; HTTP/2 uses
 * per-frame 24-bit length fields; gRPC uses a 4-byte big-endian length prefix. Corruption of these
 * fields causes the receiver to misinterpret frame boundaries and will typically produce a parse
 * error within a few frames. The injection tests whether the parse error handling is robust and
 * does not cause crashes, memory overreads, or data loss.
 *
 * <p>The two-level probability model ({@link #toxicity} per-call, {@link #rate} per-byte) allows
 * independent control of how frequently corruption events occur and how severe each event is. A low
 * toxicity (e.g., 0.01) with moderate rate (e.g., 0.01) produces infrequent but noticeable
 * corruption; a high toxicity with very low rate (e.g., 0.001) produces frequent events with
 * typically one corrupted byte per event.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosRecvCorrupt(rate = 0.001, toxicity = 0.01)
 * class RecvCorruptTest {
 *   @Test
 *   void protocolParserDetectsAndRejectsCorruptedFrames(ConnectionInfo info) {
 *     // assert that parse errors are detected and connections are re-established
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRecvEconnreset
 * @see ChaosRecvLatency
 */
@Repeatable(ChaosRecvCorrupt.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionCorruptTranslator")
public @interface ChaosRecvCorrupt {

  /**
   * @return per-byte bit-flip probability when the rule fires, in {@code (0.0, 1.0]}
   */
  double rate() default 0.001;

  /**
   * @return per-call match probability, in {@code (0.0, 1.0]}
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
   * @ChaosRecvCorrupt(id = "primary",  probability = 0.001)
   * @ChaosRecvCorrupt(id = "replica",  probability = 0.01)
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
    ChaosRecvCorrupt[] value();
  }
}
