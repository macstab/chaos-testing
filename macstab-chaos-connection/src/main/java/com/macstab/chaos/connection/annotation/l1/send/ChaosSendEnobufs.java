/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.send;

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
 * Injects {@code ENOBUFS} into {@code send(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOBUFS} as if the kernel's network buffer pool is exhausted and cannot allocate
 * a buffer to queue the outgoing data for transmission.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SEND}, errno = {@code ENOBUFS})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code send} call; when it fires the interposer returns {@code -1} with {@code errno = ENOBUFS}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code send} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ENOBUFS}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOBUFS} on {@code send} indicates a kernel-level network memory pressure condition;
 *       unlike {@code EAGAIN} (send buffer full due to flow control), {@code ENOBUFS} means the
 *       kernel's global socket buffer pool (sk_buff allocator) is exhausted. The application should
 *       back off sending rather than retrying immediately.
 *   <li>Assert that the application does not close the connection on {@code ENOBUFS}; the connection
 *       remains valid and sending can resume once kernel memory pressure is relieved.
 *   <li>UDP senders that generate many datagrams per second are most susceptible to this error;
 *       assert that the UDP sender reduces its transmission rate when it receives {@code ENOBUFS}
 *       and gradually resumes as conditions improve.
 *   <li>Assert that the application emits a kernel buffer exhaustion metric on {@code ENOBUFS},
 *       enabling operators to tune {@code net.core.wmem_max} and {@code net.core.wmem_default}.
 * </ul>
 *
 * <p>In production, {@code ENOBUFS} from {@code send} occurs when the system is generating network
 * traffic faster than the kernel can allocate sk_buff structures (the kernel's network buffer
 * descriptor structure), typically during high-volume UDP multicast, DNS response storms, or
 * broadcast traffic that generates many small sk_buff allocations simultaneously.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's network stack uses a slab allocator to manage sk_buff structures. Each sk_buff
 * represents one packet or datagram in the kernel's queuing system; when the slab cache is
 * exhausted (due to memory pressure or excessive packet rate), the allocator returns NULL and the
 * network stack returns {@code ENOBUFS} to the calling application. The condition is system-wide
 * and affects all processes, not just the one receiving the error.
 *
 * <p>For TCP sockets, {@code ENOBUFS} is uncommon because TCP's flow control naturally limits the
 * rate of data injection into the kernel's network stack. For UDP sockets without flow control,
 * a sender that generates packets faster than the network can transmit them will fill the sk_buff
 * slab and trigger {@code ENOBUFS}. This is distinct from {@code EAGAIN}: the latter indicates
 * the per-socket send buffer is full (a flow-control signal), while {@code ENOBUFS} indicates
 * global kernel memory exhaustion (a resource-pressure signal).
 *
 * <p>Java maps {@code ENOBUFS} from {@code send} to a {@code SocketException} with the message
 * "No buffer space available". Application code that catches {@code SocketException} and checks
 * for this message text to identify the condition should be aware that the message text varies
 * across JVM implementations and glibc versions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSendEnobufs(toxicity = 0.1)
 * class SendEnobufsTest {
 *   @Test
 *   void udpSenderReducesRateOnKernelBufferExhaustion(ConnectionInfo info) {
 *     // assert that the sender backs off and emits a buffer-exhaustion alert
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSendEagain
 * @see ChaosSendEnomem
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSendEnobufs.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SEND, errno = Errno.ENOBUFS)
public @interface ChaosSendEnobufs {

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
   * @ChaosSendEnobufs(id = "primary",  probability = 0.001)
   * @ChaosSendEnobufs(id = "replica",  probability = 0.01)
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
    ChaosSendEnobufs[] value();
  }
}
