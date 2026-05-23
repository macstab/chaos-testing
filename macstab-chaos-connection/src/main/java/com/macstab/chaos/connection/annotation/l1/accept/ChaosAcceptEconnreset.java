/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.accept;

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
 * Injects {@code ECONNRESET} into {@code accept(2)}, causing the call to return {@code -1} with
 * {@code errno = ECONNRESET} as if the connecting peer sent a TCP RST segment while the connection
 * was still in the accept queue.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ACCEPT}, errno =
 * {@code ECONNRESET}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code accept} call; when it fires the interposer returns {@code -1} with
 * {@code errno = ECONNRESET} without performing any real kernel operation. No runtime
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
 *   <li>On every intercepted {@code accept} call a Bernoulli trial with probability
 *       {@link #toxicity} is conducted; when it fires the interposer returns {@code -1} and
 *       sets {@code errno = ECONNRESET}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The server-side accept loop receives a reset error before a connection fd is obtained;
 *       no socket is accepted, so there is nothing to close — the server must simply log the
 *       event and continue accepting subsequent connections.
 *   <li>Server implementations that treat {@code ECONNRESET} from {@code accept} as a fatal
 *       error and stop accepting connections will fail to serve subsequent clients, revealing
 *       an incorrect error-recovery strategy.
 *   <li>Assert that the server's accept loop continues to accept connections after a
 *       {@code ECONNRESET} event and that the error is logged at the appropriate severity
 *       level (typically WARN, not ERROR or FATAL).
 * </ul>
 *
 * <p>In production, {@code ECONNRESET} from {@code accept} occurs when a client sends a TCP RST
 * packet after completing the three-way handshake but before the server calls {@code accept}. This
 * is common under load-balancer health checks that open and immediately close connections, or when
 * a client crashes between the SYN and the application-level accept.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Linux's TCP stack places completed connections into the accept queue of the listening socket.
 * If the client sends a RST while the connection is in the queue (before {@code accept} is called),
 * the kernel removes the connection from the queue. When the application calls {@code accept} and
 * the removed connection is selected, the kernel returns {@code ECONNRESET}. The connection fd is
 * never created — the server does not need to call {@code close}.
 *
 * <p>This is a subtle but important distinction from {@code ECONNRESET} on an established
 * connection (returned by {@code recv} or {@code send}): in that case a fd exists and must be
 * closed. Server-side code that handles both cases with the same code path may leak file
 * descriptors if it calls {@code close(-1)} after an accept-time reset.
 *
 * <p>Netty's {@code NioServerSocketChannel} and Netty's accept loop treat {@code ECONNRESET} from
 * {@code accept} as a warning-level event and continue the loop; pure Java {@code ServerSocket}
 * maps it to an {@code IOException} that propagates to the application. This injection exercises
 * both paths depending on whether the underlying framework uses native or Java I/O.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosAcceptEconnreset(toxicity = 0.01)
 * class AcceptEconnresetTest {
 *   @Test
 *   void serverAcceptLoopContinuesAfterResetByPeer(ConnectionInfo info) {
 *     // assert that the server continues accepting subsequent connections
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAcceptEagain
 * @see ChaosAcceptEmfile
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosAcceptEconnreset.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.ACCEPT, errno = Errno.ECONNRESET)
public @interface ChaosAcceptEconnreset {

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
   * @ChaosAcceptEconnreset(id = "primary",  probability = 0.001)
   * @ChaosAcceptEconnreset(id = "replica",  probability = 0.01)
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
    ChaosAcceptEconnreset[] value();
  }
}
