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
 * Injects {@code EAFNOSUPPORT} into {@code socket(2)}, causing the call to return {@code -1} with
 * {@code errno = EAFNOSUPPORT} as if the requested address family ({@code AF_INET6}, {@code AF_UNIX},
 * etc.) is not compiled into the kernel or is disabled on the host, preventing the socket from
 * being created.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SOCKET}, errno = {@code EAFNOSUPPORT})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code socket} call; when it fires the interposer returns {@code -1} with
 * {@code errno = EAFNOSUPPORT} without performing any real kernel operation. No runtime
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
 *       {@code errno = EAFNOSUPPORT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EAFNOSUPPORT} from {@code socket} is a non-retriable configuration error: the
 *       requested address family is not available on this host. Assert that the application reports
 *       a clear configuration error rather than entering a retry loop, since retrying will not
 *       resolve the address-family mismatch.
 *   <li>Applications that attempt IPv6-first connections and fall back to IPv4 (Happy Eyeballs
 *       algorithm) should handle {@code EAFNOSUPPORT} on {@code socket(AF_INET6)} as a signal to
 *       skip IPv6 and proceed directly with IPv4; assert that the fallback is triggered correctly.
 *   <li>Assert that the application does not propagate {@code EAFNOSUPPORT} as a generic
 *       "connection failed" error — the distinction between "address family not supported" and
 *       "connection refused" has operational significance for diagnostics.
 *   <li>Container environments that disable IPv6 via {@code sysctl net.ipv6.conf.all.disable_ipv6=1}
 *       will cause {@code socket(AF_INET6)} to return {@code EAFNOSUPPORT}; assert that the
 *       application is configured to tolerate IPv6-disabled environments.
 * </ul>
 *
 * <p>In production, {@code EAFNOSUPPORT} from {@code socket} occurs when a container or host is
 * configured without IPv6 support (common in hardened Kubernetes node configurations), when an
 * application compiled for IPv6 is deployed on an IPv4-only host, and when a Unix domain socket
 * application ({@code AF_UNIX}) is deployed in a container that restricts the available socket
 * families through a seccomp profile or AppArmor policy.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's {@code socket(2)} implementation looks up the requested address family in its
 * protocol family table. If the family (e.g., {@code AF_INET6 = 10}) is not registered — either
 * because the corresponding kernel module ({@code ipv6.ko}) is not loaded or because the kernel
 * was compiled without support — the kernel returns {@code EAFNOSUPPORT}. The error is distinct
 * from {@code EPROTONOSUPPORT}: {@code EAFNOSUPPORT} means the address family itself is unknown,
 * while {@code EPROTONOSUPPORT} means the address family is known but the requested protocol
 * within it is not supported.
 *
 * <p>The Happy Eyeballs algorithm (RFC 8305) attempts {@code socket(AF_INET6)} first and falls
 * back to {@code socket(AF_INET)} when the IPv6 attempt fails. If {@code EAFNOSUPPORT} is returned
 * on the IPv6 socket call, a correct implementation should immediately fall back to IPv4 rather
 * than racing the two connections. Many JVM networking libraries implement Happy Eyeballs
 * implicitly through {@code InetAddress.getByName} resolution order; the address family preference
 * is controlled by JVM flags like {@code java.net.preferIPv4Stack} and {@code java.net.preferIPv6Addresses}.
 *
 * <p>Java maps {@code EAFNOSUPPORT} from {@code socket} to a {@code SocketException} with the
 * message "Protocol family unavailable" (glibc) or "Address family not supported by protocol"
 * (on some platforms). Application code that inspects the message text should be aware of this
 * platform variation; inspecting the exception type ({@code SocketException}) and using the
 * address-family context from the connection attempt is more reliable.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSocketEafnosupport(toxicity = 0.5)
 * class SocketEafnosupportTest {
 *   @Test
 *   void ipv6SocketCreationFailureFallsBackToIpv4(ConnectionInfo info) {
 *     // assert that the client falls back to IPv4 when IPv6 socket creation is rejected
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSocketEprotonosupport
 * @see ChaosSocketEnomem
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSocketEafnosupport.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SOCKET, errno = Errno.EAFNOSUPPORT)
public @interface ChaosSocketEafnosupport {

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
   * @ChaosSocketEafnosupport(id = "primary",  probability = 0.001)
   * @ChaosSocketEafnosupport(id = "replica",  probability = 0.01)
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
    ChaosSocketEafnosupport[] value();
  }
}
