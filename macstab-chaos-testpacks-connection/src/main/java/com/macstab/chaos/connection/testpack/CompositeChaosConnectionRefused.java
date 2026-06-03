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
 * <p>Every TCP {@code connect()} syscall returns {@code ECONNREFUSED}. From the client's
 * perspective the service is completely down: no connection is ever established, and every
 * attempt is rejected immediately with a TCP RST. Applications that rely on connection-pool
 * warm-up or that do not implement circuit breakers will spam repeated connect calls until their
 * thread pool is exhausted.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.errno(Endpoint.wildcard(), NetOperation.CONNECT,
 * Errno.ECONNREFUSED, toxicity)} via libchaos-net. In production this happens when the target
 * process has crashed but its container is still scheduled, when a firewall sends TCP RST
 * instead of silently dropping packets, or when a Kubernetes {@code Service} has no ready
 * endpoints and the kernel rejects the SYN immediately.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Every connection attempt fails instantly. Connection pools cannot warm, health checks fail,
 * and circuit breakers must open to prevent cascading retry storms. Services without circuit
 * breakers will exhaust their thread pools within seconds. Operator intervention — or
 * automatic circuit-break recovery — is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>TCP RST on connect as a standard connection-refused scenario is documented in W. Richard
 * Stevens, <em>Unix Network Programming</em> (3rd ed.), §5.4: "Connection Refused". ECONNREFUSED
 * is the canonical kernel response when no process is listening on the target port.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosConnectionRefused
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
@Repeatable(CompositeChaosConnectionRefused.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.ConnectionRefusedComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosConnectionRefused {

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
    CompositeChaosConnectionRefused[] value();
  }
}
