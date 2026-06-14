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
 * <p>Downstream services respond slowly — every connect call is delayed by {@code latencyMs}
 * milliseconds, and a fraction of send calls fail with {@code EPIPE} (broken pipe), simulating a
 * downstream that starts processing requests but drops the connection mid-write. Combines two
 * failure modes: slow connection establishment (tests connection-timeout configuration) and
 * intermittent send failures (tests write-error handling and request retry logic).
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies two rules via libchaos-net:
 *
 * <ol>
 *   <li>{@code NetRule.latency(wildcard, CONNECT, latencyMs, 1.0)} — delays every connect
 *   <li>{@code NetRule.errno(wildcard, SEND, EPIPE, sendFailToxicity)} — random broken-pipe on send
 * </ol>
 *
 * In production this pattern occurs when a downstream service is overloaded: the TCP handshake
 * completes slowly because the server accept queue is full, and already-accepted connections are
 * closed mid-response when the server GC pauses or runs out of heap.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Services with connection timeouts shorter than {@code latencyMs} will fail to establish new
 * connections. Existing connections receiving EPIPE must either retry the full request (idempotent
 * operations only) or propagate the error. Request pipelines that assume send never fails will
 * silently drop data — a dangerous data-loss scenario for write-heavy workloads.
 *
 * <h2>Industry references</h2>
 *
 * <p>Slow downstream is the canonical chaos scenario for testing connection-pool timeout
 * configuration, described in Netflix's "Principles of Chaos Engineering" paper (2016). EPIPE on
 * send is covered in Stevens "Unix Network Programming" §5.12 and is the primary error surfaced by
 * Tomcat/Undertow when a client disconnects mid-request.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosSlowDownstream(latencyMs = 2000, sendFailToxicity = 0.1)
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
@Repeatable(CompositeChaosSlowDownstream.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.SlowDownstreamComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosSlowDownstream {

  /** Connect latency in milliseconds. Defaults to {@code 500}. */
  long latencyMs() default 500L;

  /**
   * Probability that any given send call fails with {@code EPIPE}. Defaults to {@code 0.05} —
   * low-rate broken-pipe to test write-error resilience without dominating the failure scenario.
   */
  double sendFailToxicity() default 0.05;

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
    CompositeChaosSlowDownstream[] value();
  }
}
