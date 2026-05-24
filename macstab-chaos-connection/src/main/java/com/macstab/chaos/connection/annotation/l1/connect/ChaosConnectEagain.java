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
 * Injects {@code EAGAIN} into {@code connect(2)}, causing the call to return {@code -1} with {@code
 * errno = EAGAIN} as if the kernel's ephemeral port range is exhausted and no local port can be
 * assigned for the outgoing connection.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code CONNECT}, errno = {@code
 * EAGAIN}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code connect} call; when it fires the interposer returns {@code -1} with {@code errno = EAGAIN}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code connect} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EAGAIN}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Connection pool acquisition fails transiently; assert that the pool client retries with
 *       exponential back-off rather than propagating the failure immediately to the caller.
 *   <li>Services that open many short-lived outgoing connections (e.g., HTTP/1.1 without
 *       keep-alive) are the most vulnerable to ephemeral port exhaustion; assert that the service
 *       uses connection pooling or HTTP/2 multiplexing to reduce port consumption.
 *   <li>Assert that the application emits a port-exhaustion metric or alert when it receives {@code
 *       EAGAIN} from {@code connect} repeatedly, so operators can tune {@code
 *       net.ipv4.ip_local_port_range} or increase TIME_WAIT recycling.
 *   <li>Distinguish {@code EAGAIN} (no port available) from {@code ECONNREFUSED} (server not
 *       listening) in error handling: the former is a local resource limit, the latter is a remote
 *       service failure requiring different circuit-breaker logic.
 * </ul>
 *
 * <p>In production, {@code EAGAIN} from {@code connect} occurs when the kernel cannot assign an
 * ephemeral source port because the range defined by {@code net.ipv4.ip_local_port_range} (default
 * 32768–60999) is fully consumed by TIME_WAIT sockets. Services that open and close many
 * connections per second without allowing TIME_WAIT to expire exhaust the range within seconds on
 * high-traffic hosts.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>For an unbound TCP socket, {@code connect} must first allocate an ephemeral source port from
 * the kernel's local port range. The kernel scans the range looking for a port that is not in use
 * by any existing socket; when the entire range is occupied by active or TIME_WAIT sockets, the
 * scan fails and {@code connect} returns {@code EAGAIN}. The Linux kernel tunable {@code
 * net.ipv4.ip_local_port_range} controls the range bounds; increasing it to 1024–65535
 * approximately doubles the available ephemeral ports.
 *
 * <p>{@code SO_REUSEADDR} does not help with ephemeral port exhaustion from {@code connect}: that
 * option allows reuse of local addresses in TIME_WAIT for the listening side (where the port is
 * known and fixed), not for the connecting side (where the port is dynamically assigned). The
 * correct mitigations are connection pooling (to reuse established connections), HTTP/2 or QUIC
 * multiplexing (to share a single connection for multiple streams), and enabling {@code
 * net.ipv4.tcp_tw_reuse} (which allows reuse of TIME_WAIT sockets for new outgoing connections if
 * the timestamps option is enabled).
 *
 * <p>Java's NIO {@code SocketChannel.connect()} propagates the {@code EAGAIN} from the kernel as an
 * {@code IOException} with the message "Cannot assign requested address" or "Resource temporarily
 * unavailable" — the exact message depends on the JVM and glibc version. Neither message directly
 * identifies port exhaustion; application code that logs the raw exception message without
 * including the socket local address and errno value will produce diagnostics that are difficult to
 * interpret during an incident.
 *
 * <p>Connection pools (HikariCP, commons-pool) catch {@code SQLException} or {@code IOException}
 * from failed connection attempts and mark the connection as invalid, then try to create a
 * replacement. On repeated {@code EAGAIN} the pool acquisition queue fills, and callers waiting for
 * a connection will receive a pool timeout exception. This injection reveals the queue depth and
 * timeout configuration required to survive a burst of port-exhaustion failures.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosConnectEagain(toxicity = 0.01)
 * class ConnectEagainTest {
 *   @Test
 *   void connectionPoolRetriesOnEphemeralPortExhaustion(ConnectionInfo info) {
 *     // assert that pool retries with back-off and emits a port-exhaustion alert
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosConnectEconnrefused
 * @see ChaosConnectEtimedout
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosConnectEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.CONNECT, errno = Errno.EAGAIN)
public @interface ChaosConnectEagain {

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
   * @ChaosConnectEagain(id = "primary",  probability = 0.001)
   * @ChaosConnectEagain(id = "replica",  probability = 0.01)
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
    ChaosConnectEagain[] value();
  }
}
