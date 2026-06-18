/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.api;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.api.ConnectionChaos;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;

/**
 * Capability-tier interface exposing libchaos-net's syscall-level fault-injection surface.
 *
 * <p><strong>Pre-flight contract.</strong> Every method on this interface requires that the target
 * container has been prepared with libchaos-net <em>before</em> {@code container.start()} — the
 * {@code .so} is hooked via {@code LD_PRELOAD}, which the dynamic loader only honours at process
 * launch. Skipping preparation and then invoking any verb here raises {@link
 * LibchaosNotPreparedException} loudly: there is no silent fallback by design. The sanctioned way
 * to satisfy preparation is the {@code @SyscallLevelChaos} annotation on the test class, which
 * {@code ChaosTestingExtension} reads to drive {@code LibchaosTransport.prepare()}.
 *
 * <p><strong>Capability uplift over {@link ConnectionChaos}.</strong> The portable parent interface
 * ({@code addLatency}, {@code rejectConnections}, {@code limitBandwidth}, …) covers the common
 * Toxiproxy-shaped vocabulary. This interface adds operations Toxiproxy literally cannot model:
 * per-syscall granularity, the full POSIX errno palette, UDP / unix-socket / DNS-level injection,
 * recv-corruption, listen/accept faults, file-descriptor exhaustion, and direct {@link NetRule}
 * application.
 *
 * <p><strong>Lifecycle.</strong> Returned {@link RuleHandle}s identify the applied rule for later
 * surgical removal via {@link #remove}. {@link #removeAll} clears every rule this strategy has
 * applied to the container.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * class MyTest {
 *   @Test
 *   void simulatesDnsOutage(RedisConnectionInfo info, ConnectionChaos chaos) {
 *     AdvancedConnectionChaos adv = (AdvancedConnectionChaos) chaos;
 *     RuleHandle h = adv.failDnsResolve(container, "redis.internal", Errno.EHOSTUNREACH, 1.0);
 *     // ...
 *     adv.remove(container, h);
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface AdvancedConnectionChaos extends ConnectionChaos {

  // ==================== Generic rule API ====================

  /**
   * Apply a single libchaos-net rule.
   *
   * @param container target container (must be prepared with libchaos-net)
   * @param rule rule to apply
   * @return handle identifying the rule for later removal
   * @throws NullPointerException if any argument is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-net is not active on {@code container}
   */
  RuleHandle apply(GenericContainer<?> container, NetRule rule);

  /**
   * Apply a batch of libchaos-net rules in a single round-trip.
   *
   * <p>Implementations <strong>must</strong> validate every rule before committing any of them
   * (fail-fast semantics). On success, returns one handle per input rule, in the same order.
   *
   * @param container target container (must be prepared with libchaos-net)
   * @param rules non-null, possibly empty list of rules
   * @return list of handles, one per rule, in the same order as {@code rules}; empty when {@code
   *     rules} is empty
   * @throws NullPointerException if any argument or rule is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-net is not active on {@code container}
   */
  List<RuleHandle> applyAll(GenericContainer<?> container, List<NetRule> rules);

  /**
   * Surgically remove a single previously-applied rule.
   *
   * <p>Idempotent — silently no-op if the handle is unknown to this strategy (e.g. already
   * removed).
   *
   * @param container target container (must be prepared with libchaos-net)
   * @param handle handle returned by a previous {@link #apply} or {@link #applyAll} call
   * @throws NullPointerException if any argument is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-net is not active on {@code container}
   */
  void remove(GenericContainer<?> container, RuleHandle handle);

  /**
   * Remove every rule this strategy has applied to {@code container}. Toxiproxy-side rules and
   * other strategies' rules are untouched.
   *
   * @param container target container (must be prepared with libchaos-net)
   * @throws NullPointerException if {@code container} is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-net is not active on {@code container}
   */
  void removeAll(GenericContainer<?> container);

  // ==================== Convenience verbs ====================

  /**
   * Make DNS resolution of {@code hostname} fail with the given errno (intercepts {@code
   * getaddrinfo}).
   *
   * @param container target container
   * @param hostname DNS name to fail
   * @param errno errno to inject (typically {@link Errno#EHOSTUNREACH} or {@link
   *     Errno#ENETUNREACH})
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle failDnsResolve(
      GenericContainer<?> container, String hostname, Errno errno, double toxicity);

  /**
   * Corrupt inbound payload on {@code recv}-family syscalls at {@code endpoint} with the given
   * rate.
   *
   * @param container target container
   * @param endpoint endpoint to corrupt
   * @param rate corruption rate in {@code (0.0, 1.0]}
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle corruptRecv(
      GenericContainer<?> container, Endpoint endpoint, double rate, double toxicity);

  /**
   * Force {@code poll}/{@code select}/{@code epoll_wait} to time out at {@code endpoint} after
   * {@code timeout}.
   *
   * @param container target container
   * @param endpoint endpoint observed by the polling loop
   * @param timeout strictly positive timeout
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle forcePollTimeout(
      GenericContainer<?> container, Endpoint endpoint, Duration timeout, double toxicity);

  /**
   * Fail a UDP operation at {@code host:port} with a specific errno.
   *
   * @param container target container
   * @param host UDP target host
   * @param port UDP target port
   * @param operation syscall to fail (typically {@link NetOperation#SEND} or {@link
   *     NetOperation#RECV})
   * @param errno errno to inject
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle failUdp(
      GenericContainer<?> container,
      String host,
      int port,
      NetOperation operation,
      Errno errno,
      double toxicity);

  /**
   * Refuse a unix-domain-socket {@code connect} with a specific errno.
   *
   * @param container target container
   * @param path absolute filesystem path of the unix socket
   * @param errno errno to inject (typically {@link Errno#ECONNREFUSED})
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle refuseUnix(GenericContainer<?> container, String path, Errno errno, double toxicity);

  /**
   * Server-side: fail {@code listen()} at {@code endpoint} with a specific errno.
   *
   * @param container target container
   * @param endpoint endpoint that the server tries to listen on
   * @param errno errno to inject (typically {@link Errno#EADDRINUSE} or {@link
   *     Errno#EADDRNOTAVAIL})
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle failListen(
      GenericContainer<?> container, Endpoint endpoint, Errno errno, double toxicity);

  /**
   * Server-side: fail {@code accept()} at {@code endpoint} with a specific errno.
   *
   * @param container target container
   * @param endpoint endpoint where new connections arrive
   * @param errno errno to inject
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle failAccept(
      GenericContainer<?> container, Endpoint endpoint, Errno errno, double toxicity);

  /**
   * Simulate file-descriptor exhaustion: {@code socket()} returns {@link Errno#EMFILE} (per-process
   * FD limit) at the given toxicity.
   *
   * @param container target container
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle exhaustFds(GenericContainer<?> container, double toxicity);
}
