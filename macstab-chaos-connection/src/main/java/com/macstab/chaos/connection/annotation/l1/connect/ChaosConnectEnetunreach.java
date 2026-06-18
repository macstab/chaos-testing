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
 * Injects {@code ENETUNREACH} into {@code connect(2)}, causing the call to return {@code -1} with
 * {@code errno = ENETUNREACH} as if the kernel's routing table contains no route to the destination
 * network and the packet cannot be forwarded.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code CONNECT}, errno = {@code
 * ENETUNREACH}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code connect} call; when it fires the interposer returns {@code -1} with {@code
 * errno = ENETUNREACH} without performing any real kernel operation. No runtime operation-errno
 * validation is needed.
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
 *       errno = ENETUNREACH}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENETUNREACH} indicates a total absence of routing to the destination network; unlike
 *       {@code EHOSTUNREACH} (which implies a route to the network exists but the host is down),
 *       this error means the routing table has no entry for the destination prefix at all.
 *   <li>Assert that the application's connection pool quarantines the affected remote endpoint and
 *       does not retry immediately; routing convergence after a partition event typically takes
 *       several seconds to minutes, making fast retry wasteful.
 *   <li>Multi-datacenter services that maintain connections to remote clusters must detect {@code
 *       ENETUNREACH} and activate their inter-datacenter failover path; assert that the failover
 *       activates within the configured detection timeout.
 *   <li>Assert that the application reports the network-partition condition at an appropriate
 *       severity level so that on-call engineers are paged rather than the error being silently
 *       counted in a low-priority metric.
 * </ul>
 *
 * <p>In production, {@code ENETUNREACH} from {@code connect} occurs during network partitions where
 * the routing table loses its default route (e.g., a BGP session drops and the kernel flushes the
 * route), when a container's network namespace is misconfigured and lacks a route to the pod CIDR
 * range, and during network interface failures where the only route to a network segment goes down.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel returns {@code ENETUNREACH} from {@code connect} when the routing lookup for the
 * destination address returns a "no route" result — neither the main routing table nor the policy
 * routing tables contain an entry for the destination network. This differs from {@code
 * EHOSTUNREACH} in that {@code ENETUNREACH} does not involve sending any packet at all; the failure
 * is purely local (routing table lookup) rather than involving ICMP responses from a remote router.
 *
 * <p>In Linux's routing model, a missing route typically means neither a specific route for the
 * destination prefix nor a default route (0.0.0.0/0) exists. Container network plugins (Flannel,
 * Calico, Cilium) install routes to pod CIDRs in the host kernel's routing table; when a network
 * plugin crashes or a route advertisement is withdrawn, connections to pods on other nodes fail
 * with {@code ENETUNREACH}. This is a common failure mode in Kubernetes during CNI plugin upgrades.
 *
 * <p>Java maps {@code ENETUNREACH} to a {@code NoRouteToHostException} with the message "Network is
 * unreachable". Note that Java uses the same exception class for both {@code ENETUNREACH} and
 * {@code EHOSTUNREACH}; application code that catches {@code NoRouteToHostException} cannot
 * distinguish between the two based on the exception type alone. For production diagnostics, the
 * errno value from the underlying native call must be captured via JNA or a native agent.
 *
 * <p>The behaviour of connection pools on {@code ENETUNREACH} varies: HikariCP will attempt to
 * reconnect using its {@code connectionTimeout} and {@code keepaliveTime} settings; Lettuce (Redis
 * client) will trigger its reconnect handler with exponential back-off. This injection verifies
 * that these reconnect strategies are configured with delays long enough to allow routing
 * convergence without causing excessive connection-creation load.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosConnectEnetunreach(toxicity = 0.01)
 * class ConnectEnetunreachTest {
 *   @Test
 *   void serviceActivatesInterDatacenterFailoverOnNetworkPartition(ConnectionInfo info) {
 *     // assert that failover activates within the configured detection timeout
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosConnectEhostunreach
 * @see ChaosConnectEtimedout
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosConnectEnetunreach.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.CONNECT, errno = Errno.ENETUNREACH)
public @interface ChaosConnectEnetunreach {

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
   * @ChaosConnectEnetunreach(id = "primary",  probability = 0.001)
   * @ChaosConnectEnetunreach(id = "replica",  probability = 0.01)
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
    ChaosConnectEnetunreach[] value();
  }
}
