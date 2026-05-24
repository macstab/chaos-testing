/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.recv;

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
 * Injects {@code ENOBUFS} into {@code recv(2)}, causing the call to return {@code -1} with {@code
 * errno = ENOBUFS} as if the kernel's network receive buffer pool is exhausted and cannot allocate
 * a buffer to hold the arriving datagram.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RECV}, errno = {@code ENOBUFS})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted {@code
 * recv} call; when it fires the interposer returns {@code -1} with {@code errno = ENOBUFS} without
 * performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *       is conducted; when it fires the interposer returns {@code -1} and sets {@code errno =
 *       ENOBUFS}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOBUFS} on {@code recv} indicates that the kernel's socket buffer pool is
 *       exhausted; unlike {@code EAGAIN} (no data available yet), {@code ENOBUFS} means the kernel
 *       cannot hold the incoming data. The application should back off and reduce ingestion rate.
 *   <li>Assert that the application does not close the connection on {@code ENOBUFS}; the
 *       connection may still be valid and retrying recv after a short delay may succeed once buffer
 *       pressure is reduced.
 *   <li>{@code ENOBUFS} is more commonly seen on UDP sockets (where each datagram requires a
 *       separate buffer allocation) than on TCP sockets; assert that the application's UDP receive
 *       loop handles the error gracefully and does not drop the processing thread.
 *   <li>Assert that the application emits a buffer-exhaustion metric when it encounters {@code
 *       ENOBUFS}, so that operators can tune {@code net.core.rmem_max} and {@code
 *       net.core.rmem_default} to reduce the frequency of the error.
 * </ul>
 *
 * <p>In production, {@code ENOBUFS} from {@code recv} occurs on UDP-based protocols (DNS, QUIC,
 * custom datagram protocols) when incoming traffic exceeds the rate at which the application drains
 * the receive buffer, causing the kernel's socket buffer to fill and the kernel to drop subsequent
 * packets with a corresponding {@code ENOBUFS} when the application calls {@code recv}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ENOBUFS} on {@code recv} is uncommon for TCP sockets because TCP's flow control
 * prevents the remote sender from overrunning the receiver's buffer; the kernel advertises a
 * reduced receive window when the buffer is nearly full, and the sender slows down. For UDP
 * sockets, there is no flow control: the kernel drops datagrams when the socket receive buffer is
 * full and may return {@code ENOBUFS} to indicate the overrun condition.
 *
 * <p>The kernel tracks per-socket buffer usage via the socket's {@code sk_rcvbuf} limit. When a
 * datagram arrives and {@code sk_rmem_alloc} exceeds {@code sk_rcvbuf}, the kernel calls {@code
 * sock_drop} and increments the {@code InErrors} counter in {@code /proc/net/udp}. Applications
 * that monitor this counter can detect packet loss without waiting for an application-level {@code
 * ENOBUFS}.
 *
 * <p>Java's {@code DatagramSocket} and {@code DatagramChannel} map {@code ENOBUFS} from {@code
 * recv} to a {@code SocketException} with the message "No buffer space available". This is the same
 * message used for {@code ENOBUFS} from {@code send}; application code that relies on the message
 * text to classify the error may not correctly distinguish the two directions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosRecvEnobufs(toxicity = 0.1)
 * class RecvEnobufsTest {
 *   @Test
 *   void udpReceiverBacksOffAndEmitsBufferExhaustionMetricOnEnobufs(ConnectionInfo info) {
 *     // assert that the receiver backs off and emits a buffer-exhaustion alert
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRecvEconnreset
 * @see ChaosRecvEagain
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosRecvEnobufs.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.RECV, errno = Errno.ENOBUFS)
public @interface ChaosRecvEnobufs {

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
   * @ChaosRecvEnobufs(id = "primary",  probability = 0.001)
   * @ChaosRecvEnobufs(id = "replica",  probability = 0.01)
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
    ChaosRecvEnobufs[] value();
  }
}
