/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.listen;

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
 * Injects {@code EOPNOTSUPP} into {@code listen(2)}, causing the call to return {@code -1} with
 * {@code errno = EOPNOTSUPP} as if the socket type does not support the listening operation —
 * the most common real cause being calling {@code listen} on a {@code SOCK_DGRAM} UDP socket.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code LISTEN}, errno =
 * {@code EOPNOTSUPP}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code listen} call; when it fires the interposer returns {@code -1} with
 * {@code errno = EOPNOTSUPP} without performing any real kernel operation. No runtime
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
 *   <li>On each intercepted {@code listen} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EOPNOTSUPP}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EOPNOTSUPP} from {@code listen} indicates a fundamental protocol mismatch — the
 *       socket type does not support connection-oriented listening; this is always a programming
 *       error and must never be retried.
 *   <li>Assert that the application terminates the startup sequence immediately and logs the socket
 *       type ({@code SOCK_DGRAM}, {@code SOCK_RAW}) that caused the failure, so developers can
 *       identify the wrong socket type in the socket creation call.
 *   <li>Applications that abstract socket creation behind a factory or configuration layer may
 *       silently create the wrong socket type when configuration is misread; this injection tests
 *       that such errors are reported clearly rather than causing cryptic downstream failures.
 *   <li>Distinguish {@code EOPNOTSUPP} (wrong socket type — cannot be fixed without recreating the
 *       socket) from {@code EINVAL} (wrong state or parameter — wrong lifecycle phase but same
 *       socket type); the former requires changing the {@code socket(2)} call, the latter requires
 *       fixing the call sequence.
 * </ul>
 *
 * <p>In production, {@code EOPNOTSUPP} from {@code listen} is rare but occurs when a service is
 * incorrectly configured to use UDP for a TCP-based protocol, when a generic networking abstraction
 * layer creates the wrong socket type based on a misconfigured socket type constant, and during
 * socket migration when a socket file descriptor is passed across process boundaries and the
 * receiving process assumes a different socket type than was created.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX defines {@code listen} as meaningful only for connection-oriented socket types:
 * {@code SOCK_STREAM} (TCP) and {@code SOCK_SEQPACKET} (SCTP, Unix domain sequenced-packet sockets).
 * Calling {@code listen} on a {@code SOCK_DGRAM} (UDP) or {@code SOCK_RAW} socket returns
 * {@code EOPNOTSUPP} because these socket types have no concept of a connection queue — they receive
 * datagrams from any sender without a connection establishment phase.
 *
 * <p>The error is significant for generic socket abstraction layers used in embedded systems,
 * protocol-agnostic middleware, and network testing frameworks: if the layer selects the socket type
 * based on a configuration parameter, a misconfiguration can cause {@code listen} to fail with
 * {@code EOPNOTSUPP}. This injection verifies that the abstraction layer propagates the error
 * clearly rather than swallowing it or converting it to a generic "socket setup failed" message.
 *
 * <p>Java's {@code ServerSocket} only creates {@code SOCK_STREAM} sockets and will never encounter
 * {@code EOPNOTSUPP} from the JVM's own calls. This error path is relevant to native code called
 * via JNI, socket file descriptors imported from native libraries, and tests of native socket
 * setup code wrapped behind a Java API.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosListenEopnotsupp(toxicity = 0.001)
 * class ListenEopnotsuppTest {
 *   @Test
 *   void serverReportsSocketTypeMismatchAndFailsFast(ConnectionInfo info) {
 *     // assert that the server logs the socket type and does not retry the listen call
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosListenEinval
 * @see ChaosListenEaddrinuse
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosListenEopnotsupp.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.LISTEN, errno = Errno.EOPNOTSUPP)
public @interface ChaosListenEopnotsupp {

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
   * @ChaosListenEopnotsupp(id = "primary",  probability = 0.001)
   * @ChaosListenEopnotsupp(id = "replica",  probability = 0.01)
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
    ChaosListenEopnotsupp[] value();
  }
}
