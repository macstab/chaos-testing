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
 * <p>Every {@code bind()} call returns {@code EADDRNOTAVAIL} — the kernel cannot assign a local
 * ephemeral port to the new socket. Applications that create outbound connections fail because the
 * TCP/IP stack cannot allocate a source port from the local ephemeral port range. This simulates
 * ephemeral port exhaustion under extreme connection-creation load — every new outbound socket
 * fails immediately.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.errno(wildcard, BIND, EADDRNOTAVAIL, toxicity)} via libchaos-net. Every
 * {@code bind()} syscall on the container fails with {@code EADDRNOTAVAIL}. In production this
 * happens when the kernel's ephemeral port range ({@code /proc/sys/net/ipv4/ip_local_port_range}) is
 * saturated — typically at 28,232 simultaneous connections per destination — or during TIME_WAIT
 * accumulation after high-frequency connection cycling.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Every new outbound connection fails. Existing connections are unaffected, making the failure mode
 * partial and hard to diagnose: established pool connections work while new connection attempts fail
 * silently. Connection pools that do not health-check on borrow surface as cascading
 * {@code ConnectException} storms when the pool becomes exhausted and new connections are needed.
 *
 * <h2>Industry references</h2>
 *
 * <p>Ephemeral port exhaustion is documented in AWS re:Post ("TCP port exhaustion on EC2 instances")
 * and in the Linux kernel networking FAQ. The TIME_WAIT accumulation variant is described in
 * Cloudflare's blog "How TCP sockets work" and in the NGINX performance tuning guide.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosSocketEphemeralExhaustion
 * class MyResilienceTest {
 *
 *   @ServiceHealthCheck
 *   void healthy() { assertThat(service.ping()).isEqualTo("ok"); }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosSocketEphemeralExhaustion.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.SocketEphemeralExhaustionComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosSocketEphemeralExhaustion {

  /**
   * Probability that any given {@code bind()} call fails. Defaults to {@code 1.0} (all calls fail).
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
    CompositeChaosSocketEphemeralExhaustion[] value();
  }
}
