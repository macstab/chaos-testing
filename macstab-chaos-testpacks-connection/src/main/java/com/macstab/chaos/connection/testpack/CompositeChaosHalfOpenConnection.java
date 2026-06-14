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
 * <p>Inbound receive calls fail with {@code ECONNRESET} at the configured rate, simulating
 * half-open TCP connections. The local end believes the connection is established but receives a
 * RST when it next attempts to read. Applications that keep idle connections in a pool without
 * health checks will hand out dead connections to callers, causing delayed failures when the first
 * request is sent on a half-open socket.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.errno(wildcard, RECV, ECONNRESET, toxicity)} via libchaos-net. In
 * production half-open connections occur when the remote end crashes (no FIN sent — just process
 * death), when a stateful firewall drops its state table and RSTs existing flows, or when a load
 * balancer with a shorter idle timeout than the client silently closes connections on the backend.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Connection pools with validation-on-borrow ({@code testOnBorrow=true}) recover transparently.
 * Pools without validation hand dead connections to threads; the first read attempt fails, the
 * caller must retry, and the failed connection is returned to the pool as invalid. Multiplied
 * across many threads, this can cause a brief spike of errors before the pool evicts stale
 * connections.
 *
 * <h2>Industry references</h2>
 *
 * <p>Half-open TCP connections are described in Stevens "TCP/IP Illustrated Vol. 1" §18.6. The
 * connection pool interaction is documented in HikariCP's configuration guide (testOnBorrow,
 * keepaliveTime) and in the JDBC 4.0 spec's Connection.isValid() method. AWS ALB's 60-second idle
 * timeout vs. application pool idle timeout mismatch is a frequently referenced StackOverflow
 * issue.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosHalfOpenConnection(toxicity = 0.3)
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
@Repeatable(CompositeChaosHalfOpenConnection.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.HalfOpenConnectionComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosHalfOpenConnection {

  /**
   * Probability that any given recv call returns {@code ECONNRESET}. Defaults to {@code 0.3} —
   * sporadic failures to surface half-open pool validation issues.
   */
  double toxicity() default 0.3;

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
    CompositeChaosHalfOpenConnection[] value();
  }
}
