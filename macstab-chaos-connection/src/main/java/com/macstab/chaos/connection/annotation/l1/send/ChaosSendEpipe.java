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
 * Injects {@code EPIPE} into {@code send(2)}, causing the call to return {@code -1} with
 * {@code errno = EPIPE} as if the remote peer closed its end of the connection gracefully (FIN),
 * the application continued sending data, and the remote peer responded with a TCP RST to reject
 * the unexpected data — producing the broken-pipe condition.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SEND}, errno = {@code EPIPE})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code send} call; when it fires the interposer returns {@code -1} with {@code errno = EPIPE}
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
 *       {@code errno = EPIPE}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EPIPE} from {@code send} means the remote end has closed the connection; the socket
 *       is permanently unusable and must be closed and replaced. Assert that the application closes
 *       the socket immediately on {@code EPIPE} and does not attempt further sends on it.
 *   <li>By default, the kernel also sends {@code SIGPIPE} to the process when {@code EPIPE} is
 *       returned from {@code send}; if the process does not handle or ignore {@code SIGPIPE}, it
 *       will be terminated. Assert that the application either uses {@code MSG_NOSIGNAL} on send
 *       or installs a {@code SIGPIPE} handler (or sets it to {@code SIG_IGN}).
 *   <li>Connection pools must evict the connection on {@code EPIPE} and create a replacement;
 *       assert that the pool does not return a broken-pipe connection to subsequent callers.
 *   <li>Assert that the application does not blindly retry on {@code EPIPE} for non-idempotent
 *       operations: when the kernel returns {@code EPIPE} on send, some data may have reached the
 *       remote side before the peer closed the connection.
 * </ul>
 *
 * <p>In production, {@code EPIPE} from {@code send} occurs when a server closes a connection
 * (HTTP/1.1 "Connection: close", idle-timeout on a connection pool, server restart) while the
 * client's send buffer still contains data to write. It is common in connection-pooled HTTP clients
 * that do not validate connection liveness before reuse, and in long-lived TCP connections that
 * cross server maintenance windows.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>When the remote peer calls {@code close()} or {@code shutdown(SHUT_WR)}, it sends a TCP FIN
 * to the local socket. The local socket transitions to the CLOSE_WAIT state but can still send
 * data. If the application then calls {@code send()}, the kernel delivers the data; the remote
 * peer, now in a half-closed state and not expecting data, responds with a RST. On the next
 * {@code send} the kernel has received the RST, knows the pipe is broken, and returns {@code -1}
 * with {@code errno = EPIPE}. Simultaneously, the kernel raises {@code SIGPIPE} unless the
 * application passed {@code MSG_NOSIGNAL} in the send flags.
 *
 * <p>The sequence — FIN received, send attempted, RST received, next send returns EPIPE — means
 * that the first send after the peer closes may actually succeed (delivering data to the peer's
 * now-closed receive buffer). Only the second send (after the RST is received) returns
 * {@code EPIPE}. This race is relevant to idempotency: the application cannot tell whether the
 * first send was processed before the peer closed the connection.
 *
 * <p>The JVM handles {@code SIGPIPE} at startup: it sets {@code SIGPIPE} to {@code SIG_IGN} for
 * the JVM process, so Java applications are not terminated by the signal. Instead, the error is
 * surfaced as a {@code SocketException} with the message "Broken pipe". Application code that
 * wraps socket writes in a loop should treat "Broken pipe" as a non-retriable terminal error for
 * the current connection and propagate a connection-loss event to callers.
 *
 * <p>The {@code MSG_NOSIGNAL} flag (Linux-specific) prevents {@code SIGPIPE} generation on a
 * per-send-call basis without changing the process-wide signal disposition. In Java, there is no
 * direct way to pass {@code MSG_NOSIGNAL} from user code; the JVM already suppresses the signal
 * at the process level, so this is only relevant for native code called via JNI.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSendEpipe(toxicity = 0.05)
 * class SendEpipeTest {
 *   @Test
 *   void connectionPoolEvictsSocketOnBrokenPipe(ConnectionInfo info) {
 *     // assert that the pool closes the broken socket and creates a fresh connection
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSendEconnreset
 * @see ChaosSendEtimedout
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSendEpipe.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SEND, errno = Errno.EPIPE)
public @interface ChaosSendEpipe {

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
   * @ChaosSendEpipe(id = "primary",  probability = 0.001)
   * @ChaosSendEpipe(id = "replica",  probability = 0.01)
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
    ChaosSendEpipe[] value();
  }
}
