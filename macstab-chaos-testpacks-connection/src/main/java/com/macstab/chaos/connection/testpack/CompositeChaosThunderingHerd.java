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
 * <p>Simulates a connection blackout followed by a thundering herd: all clients receive {@code
 * ECONNREFUSED} during the outage window, and when the rule is removed (e.g. in {@code @AfterEach})
 * every waiting client retries simultaneously. Services that lack jittered backoff or a
 * circuit-breaker flood the recovering dependency with a synchronised burst of reconnect attempts,
 * causing a second outage just as the first one clears.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code NetRule.errno(Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED,
 * toxicity)} via libchaos-net. The thundering-herd effect emerges naturally when the chaos rule is
 * lifted at the end of each test: all blocked or retrying clients fire at once. In production this
 * happens after a brief network partition clears, after a dependency pod restarts, or when an
 * autoscaler brings new instances online and all idle clients attempt to reconnect simultaneously.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * The reconnect storm can overwhelm the recovering service, causing a second outage. Without jitter
 * in retry schedules and circuit-breaker coordination across instances, the system may oscillate
 * between outage and storm indefinitely. Data loss is possible if in-flight requests are dropped
 * during the storm. Immediate operator intervention is typically required.
 *
 * <h2>Industry references</h2>
 *
 * <p>The thundering herd problem and jittered exponential backoff as its mitigation are documented
 * in Google's <em>Site Reliability Engineering</em> book (Chapter 22: "Addressing Cascading
 * Failures") and in RFC 8305 (Happy Eyeballs v2), which defines connection-attempt intervals
 * specifically to avoid synchronised retry bursts.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @CompositeChaosThunderingHerd
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
@Repeatable(CompositeChaosThunderingHerd.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.connection.testpack.composers.ThunderingHerdComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosThunderingHerd {

  /**
   * Probability in {@code (0.0, 1.0]} that the errno fires for any matched {@code connect()} call
   * during the blackout window. Defaults to {@code 1.0} (total blackout).
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
    CompositeChaosThunderingHerd[] value();
  }
}
