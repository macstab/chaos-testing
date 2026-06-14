/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Every TCP {@code connect()} syscall returns {@code ENETUNREACH} — network is unreachable. This
 * is a subnet-level routing failure: the local machine's routing table has no entry for the
 * destination network at all. The error is returned before any packet is sent. Applications that
 * catch {@code EHOSTUNREACH} but not {@code ENETUNREACH} will propagate an unhandled exception,
 * making this a distinct and important failure path to exercise.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.errno(Endpoint.wildcard(), NetOperation.CONNECT, Errno.ENETUNREACH,
 * toxicity)} via libchaos-net. In production this happens during VPC peering misconfiguration
 * (route tables missing the peer CIDR), AWS Transit Gateway route propagation failures, network
 * namespace isolation in container runtimes, or on-premises routing table corruption after a router
 * failover.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Every outbound connection fails at the network layer before even reaching the destination host.
 * The scope is broader than {@link CompositeChaosUnreachableHost}: an entire subnet is missing from
 * the routing table, so the failure affects all services in that network range simultaneously.
 * Recovery requires a routing table fix, not a service restart.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code ENETUNREACH} during VPC peering misconfiguration is documented in AWS Networking
 * troubleshooting guides for Transit Gateway route propagation failures. Network namespace
 * isolation causing {@code ENETUNREACH} is described in the Linux kernel networking FAQ and in
 * Docker's network isolation documentation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosUnreachableNetwork
 * class MyResilienceTest {
 *   @ServiceHealthCheck
 *   void healthy() { assertThat(service.ping()).isEqualTo("ok"); }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosUnreachableNetwork.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.UnreachableNetworkComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosUnreachableNetwork {

  /**
   * Probability in {@code (0.0, 1.0]} that the errno fires for any matched {@code connect()} call.
   * Defaults to {@code 1.0} (every call fails).
   */
  double toxicity() default 1.0;

  /**
   * libchaos-net endpoint selector. Accepted forms:
   *
   * <ul>
   *   <li>{@code "*"} — wildcard; matches every socket (default)
   *   <li>{@code "tcp4://host:port"} — TCP/IPv4 to a specific host and port
   *   <li>{@code "tcp6://[host]:port"} — TCP/IPv6
   *   <li>{@code "udp4://host:port"} — UDP/IPv4
   *   <li>{@code "udp6://[host]:port"} — UDP/IPv6
   *   <li>{@code "unix:///path"} — Unix-domain socket
   *   <li>{@code "dns://hostname"} — DNS interception at {@code getaddrinfo} time
   *   <li>{@code "hostname"} — shorthand for {@code dns://hostname}
   * </ul>
   */
  String endpoint() default "*";

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-net.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosUnreachableNetwork[] value();
  }
}
