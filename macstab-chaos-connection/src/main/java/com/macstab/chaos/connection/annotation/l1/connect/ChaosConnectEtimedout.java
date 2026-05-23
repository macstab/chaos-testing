/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.connect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Injects {@code ETIMEDOUT} into {@code connect(2)}, causing the call to return {@code -1} with
 * {@code errno = ETIMEDOUT} as if the TCP SYN retransmission limit was reached without receiving a
 * SYN-ACK from the remote host, indicating the destination is silently dropping packets.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code CONNECT}, errno =
 * {@code ETIMEDOUT}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code connect} call; when it fires the interposer returns {@code -1} with
 * {@code errno = ETIMEDOUT} without performing any real kernel operation. No runtime
 * operation-errno validation is needed.
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
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ETIMEDOUT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Unlike {@code ECONNREFUSED} (immediate RST), real {@code ETIMEDOUT} from {@code connect}
 *       requires waiting through multiple SYN retransmissions (typically 75–127 seconds with default
 *       Linux settings); this injection delivers the error immediately, allowing tests to verify
 *       timeout handling without waiting.
 *   <li>Assert that application-level connection timeouts are configured below the kernel's default
 *       TCP_SYN_RETRIES timeout; an application that relies on the kernel's SYN timeout will block
 *       threads for over two minutes on each failed connection attempt.
 *   <li>Connection pool timeout settings ({@code connectionTimeout} in HikariCP) must account for
 *       the possibility of {@code ETIMEDOUT}; assert that pool acquisition timeouts are shorter than
 *       the kernel's SYN retry period so that the pool fails fast and the thread is not blocked.
 *   <li>Assert that the application treats {@code ETIMEDOUT} from {@code connect} as a "black hole"
 *       indicator and applies longer retry back-off than for {@code ECONNREFUSED}, since black-hole
 *       routes can persist for extended periods.
 * </ul>
 *
 * <p>In production, {@code ETIMEDOUT} from {@code connect} occurs when a firewall silently drops TCP
 * SYN packets (rather than rejecting them with RST), when a host is partially available (accepting
 * ICMP ping but not TCP connections), during asymmetric routing failures where SYN packets reach the
 * destination but SYN-ACK packets are lost on the return path, and when a load balancer or NAT
 * device fails and all packets to a destination are silently discarded.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>In real networking, the kernel's TCP stack sends the SYN and retransmits it with exponential
 * back-off up to {@code tcp_syn_retries} times (default 6 on Linux, giving a total timeout of about
 * 127 seconds). When all retransmissions are exhausted without a response, the kernel fails the
 * {@code connect} with {@code ETIMEDOUT}. This is the longest possible connect failure time and is
 * the "black hole" scenario: packets are sent but never acknowledged.
 *
 * <p>This injection bypasses the SYN retry mechanism entirely, delivering {@code ETIMEDOUT}
 * immediately. This allows connect-timeout handling code to be tested without requiring actual
 * black-hole routes or waiting for the kernel's SYN retry timeout to expire. For tests that need
 * to verify that the application's connect timeout fires before the kernel's SYN timeout, use
 * {@link ChaosConnectLatency} with a delay longer than the application's configured timeout instead.
 *
 * <p>Java maps {@code ETIMEDOUT} from {@code connect} to a {@code SocketTimeoutException} if a
 * {@code SO_TIMEOUT} was set on the socket, or to a generic {@code SocketException} with the message
 * "Connection timed out" if no timeout was set and the kernel's SYN retry limit was reached.
 * Application code that uses {@code SO_TIMEOUT} to interrupt long connects will receive a
 * {@code SocketTimeoutException}; code that relies on the kernel timeout will receive a
 * {@code SocketException}. Both must be handled correctly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosConnectEtimedout(toxicity = 0.05)
 * class ConnectEtimedoutTest {
 *   @Test
 *   void connectionPoolFailsFastOnBlackHoleDestination(ConnectionInfo info) {
 *     // assert pool acquisition fails within configuredTimeout and does not block for 127 seconds
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosConnectEconnrefused
 * @see ChaosConnectLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosConnectEtimedout.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.CONNECT, errno = Errno.ETIMEDOUT)
public @interface ChaosConnectEtimedout {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
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
   * @ChaosConnectEtimedout(id = "primary",  probability = 0.001)
   * @ChaosConnectEtimedout(id = "replica",  probability = 0.01)
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
    ChaosConnectEtimedout[] value();
  }
}
