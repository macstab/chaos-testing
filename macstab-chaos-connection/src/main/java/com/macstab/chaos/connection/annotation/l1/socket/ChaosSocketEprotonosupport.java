/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.socket;

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
 * Injects {@code EPROTONOSUPPORT} into {@code socket(2)}, causing the call to return {@code -1}
 * with {@code errno = EPROTONOSUPPORT} as if the requested protocol number (e.g., {@code IPPROTO_SCTP}
 * or {@code IPPROTO_DCCP}) is not registered in the kernel's protocol table for the given address
 * family, preventing the socket from being created.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SOCKET}, errno = {@code EPROTONOSUPPORT})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code socket} call; when it fires the interposer returns {@code -1} with
 * {@code errno = EPROTONOSUPPORT} without performing any real kernel operation. No runtime
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
 *   <li>On each intercepted {@code socket} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EPROTONOSUPPORT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EPROTONOSUPPORT} from {@code socket} is a non-retriable configuration error: the
 *       requested protocol is not available on this kernel. Assert that the application reports a
 *       clear startup error indicating that the required kernel protocol module is missing, rather
 *       than entering a retry loop.
 *   <li>Applications that probe for protocol support at startup (e.g., testing whether
 *       {@code IPPROTO_SCTP} is available before advertising SCTP support to peers) should handle
 *       {@code EPROTONOSUPPORT} gracefully and fall back to an alternative transport; assert that
 *       the fallback path is exercised correctly.
 *   <li>Assert that the application does not proceed with degraded configuration silently when
 *       a requested transport protocol is unavailable — missing SCTP or DCCP support should
 *       produce a clear configuration error, not a subtle protocol negotiation failure later.
 *   <li>Socket factory abstractions that support multiple protocol options should log the specific
 *       protocol that failed with {@code EPROTONOSUPPORT}, not just "socket creation failed".
 * </ul>
 *
 * <p>In production, {@code EPROTONOSUPPORT} from {@code socket} occurs when an application that
 * requires SCTP (stream control transmission protocol) is deployed on a kernel where the
 * {@code sctp.ko} module is not loaded or is blocked by a seccomp policy, when a QUIC or DCCP
 * prototype is deployed without the required kernel patches, and when a containerized process
 * requests a raw socket ({@code SOCK_RAW}) protocol that is disallowed by the container's seccomp
 * profile.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's {@code socket(2)} implementation looks up the requested (family, type, protocol)
 * tuple in its protocol registration table. For the AF_INET family, the kernel knows about
 * {@code IPPROTO_TCP} (6), {@code IPPROTO_UDP} (17), {@code IPPROTO_ICMP} (1), and other
 * registered protocols. If the requested protocol is not registered — because the kernel module
 * that implements it is not loaded (SCTP = {@code sctp.ko}, DCCP = {@code dccp.ko}) or because the
 * protocol number is simply invalid — the kernel returns {@code EPROTONOSUPPORT}.
 *
 * <p>The distinction between {@code EAFNOSUPPORT} and {@code EPROTONOSUPPORT}: {@code EAFNOSUPPORT}
 * means the address family itself is not registered ({@code AF_INET6} when IPv6 is disabled), while
 * {@code EPROTONOSUPPORT} means the address family is known but the requested protocol within it is
 * not registered. An application that uses {@code socket(AF_INET, SOCK_STREAM, IPPROTO_SCTP)} on
 * a system without the SCTP module will receive {@code EPROTONOSUPPORT} even though
 * {@code AF_INET} and {@code SOCK_STREAM} are both supported.
 *
 * <p>Java maps {@code EPROTONOSUPPORT} from {@code socket} to a {@code SocketException} with the
 * message "Protocol not supported". The standard Java networking API does not expose raw socket
 * creation with arbitrary protocol numbers; this error is primarily relevant to native networking
 * libraries (Netty's native transport, JNI-based SCTP implementations like the
 * {@code com.sun.nio.sctp} module) and to seccomp-constrained environments where common socket
 * types are blocked.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSocketEprotonosupport(toxicity = 1.0)
 * class SocketEprotonosupportTest {
 *   @Test
 *   void applicationFallsBackToTcpWhenSctpIsUnavailable(ConnectionInfo info) {
 *     // assert that the application falls back to TCP transport when SCTP socket creation fails
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSocketEafnosupport
 * @see ChaosSocketEnomem
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSocketEprotonosupport.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SOCKET, errno = Errno.EPROTONOSUPPORT)
public @interface ChaosSocketEprotonosupport {

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
   * @ChaosSocketEprotonosupport(id = "primary",  probability = 0.001)
   * @ChaosSocketEprotonosupport(id = "replica",  probability = 0.01)
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
    ChaosSocketEprotonosupport[] value();
  }
}
