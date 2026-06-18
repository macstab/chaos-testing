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
 * <p>Every {@code bind()} syscall returns {@code EADDRINUSE} — the address is already in use.
 * Server components that open listening sockets on startup fail immediately during initialisation
 * and cannot accept connections. This is a startup-time failure: the process never enters a healthy
 * state, so health checks never pass and the service is never registered with the load balancer.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.errno(Endpoint.wildcard(), NetOperation.BIND, Errno.EADDRINUSE,
 * toxicity)} via libchaos-net. In production this happens after rapid service restarts when {@code
 * SO_REUSEADDR} is not set and the OS kernel holds the port in {@code TIME_WAIT} state, when two
 * processes attempt to bind the same port simultaneously during a rolling deployment, or when a
 * stale PID file prevents the new process from inheriting the socket.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * The service fails to start but does not corrupt data. A restart or a short wait for the {@code
 * TIME_WAIT} timer to expire typically resolves the issue. Kubernetes readiness probes will keep
 * traffic away from the broken pod, so other replicas can absorb the load — unless all replicas
 * restart simultaneously (e.g. during a rolling update), in which case the service is unavailable
 * until at least one pod successfully binds.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code EADDRINUSE} during rapid port reuse without {@code SO_REUSEADDR} is documented in the
 * Linux kernel networking FAQ. AWS Container Insights troubleshooting guide lists {@code
 * EADDRINUSE} as a common failure mode for services that restart frequently and share a host
 * network namespace.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosPortAlreadyInUse
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
@Repeatable(CompositeChaosPortAlreadyInUse.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.PortAlreadyInUseComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosPortAlreadyInUse {

  /**
   * Probability in {@code (0.0, 1.0]} that the errno fires for any matched {@code bind()} call.
   * Defaults to {@code 1.0} (every bind attempt fails).
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
    CompositeChaosPortAlreadyInUse[] value();
  }
}
