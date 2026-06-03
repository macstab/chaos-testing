/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.testpack;

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
 * <p>DNS resolution takes 8 seconds (configurable) longer than normal. Applications with short HTTP
 * or JDBC connection timeouts will time out during initial connection, even though the target
 * service is healthy. This simulates a slow or overloaded name server, a DNS resolver under heavy
 * load, or a network with high UDP loss that forces multiple retransmits.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code DnsRule.latency(anyForward(), 8s)} via libchaos-dns. Every {@code
 * getaddrinfo()} call on the container is delayed by the configured latency before returning the
 * (correct) answer. In production this happens when an on-premises resolver is under load during a
 * traffic spike, or when DNSSEC validation is misconfigured and causes repeated validation rounds.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Connection-pool warming slows dramatically; first requests to any service fail if the connection
 * timeout is shorter than the DNS latency. The service typically recovers once the connection pool
 * is warm — but cold starts under this condition are fragile.
 *
 * <h2>Industry references</h2>
 *
 * <p>DNS latency as a contributing factor to application timeouts is documented in Netflix's
 * "Chaos Engineering" practitioner guide and in the Cloudflare 2023 incident post-mortems covering
 * resolver overload during DDoS events.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @CompositeChaosDnsTimeout(latencyMs = 5000)
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
@Repeatable(CompositeChaosDnsTimeout.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.dns.testpack.composers.DnsTimeoutComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosDnsTimeout {

  /** Added latency per DNS lookup in milliseconds. Defaults to 8000ms (8 seconds). */
  long latencyMs() default 8_000L;

  /**
   * Hostname to target. {@code "*"} (the default) applies the latency to all forward lookups.
   * Provide a specific hostname to limit chaos to one dependency.
   */
  String host() default "*";

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-dns.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosDnsTimeout[] value();
  }
}
