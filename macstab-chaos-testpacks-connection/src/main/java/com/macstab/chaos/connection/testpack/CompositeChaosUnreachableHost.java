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
 * <h2>What this is</h2>
 *
 * <p>Every TCP {@code connect()} syscall returns {@code EHOSTUNREACH} — no route to host.
 * Unlike {@code ECONNREFUSED}, no TCP RST is sent: the kernel determines at the IP layer
 * that the host cannot be reached and fails immediately with a routing error. Clients that
 * distinguish {@code EHOSTUNREACH} from {@code ECONNREFUSED} may apply different retry
 * policies; those that do not will retry indefinitely while burning connection-pool threads.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.errno(Endpoint.wildcard(), NetOperation.CONNECT,
 * Errno.EHOSTUNREACH, toxicity)} via libchaos-net. In production this happens when BGP
 * routes for the target subnet are withdrawn during a network topology change, when a
 * Kubernetes pod loses its overlay network attachment (e.g. a CNI plugin restart), or when
 * a firewall silently drops packets without sending an ICMP "host unreachable" response.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * All outbound connections to any destination fail immediately. Services cannot reach
 * databases, caches, or downstream APIs. Unlike connection-refused failures, the error
 * originates at the routing layer, so it cannot be resolved by restarting the target
 * service — network-level intervention or a CNI recovery is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code EHOSTUNREACH} during BGP route withdrawal is documented in RIPE NCC operational
 * reports on routing instability events. Kubernetes CNI plugin restarts producing
 * {@code EHOSTUNREACH} on all pod-to-pod connections are a known failure mode described in
 * the Kubernetes networking troubleshooting guide.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosUnreachableHost
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
@Repeatable(CompositeChaosUnreachableHost.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.UnreachableHostComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosUnreachableHost {

  /**
   * Probability in {@code (0.0, 1.0]} that the errno fires for any matched {@code connect()}
   * call. Defaults to {@code 1.0} (every call fails).
   */
  double toxicity() default 1.0;

  /**
   * libchaos-net endpoint selector. Accepted forms:
   * <ul>
   *   <li>{@code "*"} — wildcard; matches every socket (default)</li>
   *   <li>{@code "tcp4://host:port"} — TCP/IPv4 to a specific host and port</li>
   *   <li>{@code "tcp6://[host]:port"} — TCP/IPv6</li>
   *   <li>{@code "udp4://host:port"} — UDP/IPv4</li>
   *   <li>{@code "udp6://[host]:port"} — UDP/IPv6</li>
   *   <li>{@code "unix:///path"} — Unix-domain socket</li>
   *   <li>{@code "dns://hostname"} — DNS interception at {@code getaddrinfo} time</li>
   *   <li>{@code "hostname"} — shorthand for {@code dns://hostname}</li>
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
    CompositeChaosUnreachableHost[] value();
  }
}
