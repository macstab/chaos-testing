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
 * <p>The DNS resolver returns {@code EAI_NONAME} (NXDOMAIN — "no such host") for every forward
 * lookup. Applications that rely on DNS-based service discovery lose the ability to connect to any
 * downstream dependency, simulating a complete DNS resolution failure as seen when an authoritative
 * name server drops a zone or a split-horizon DNS config sends queries to the wrong server.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code DnsRule.eai(anyForward(), EAI_NONAME)} via libchaos-dns. Every {@code
 * getaddrinfo()} call on the container returns NXDOMAIN regardless of the queried hostname. In
 * production this happens when a DNS zone delegation breaks, when a CDN's edge resolver goes dark,
 * or when a firewall blocks outbound UDP 53.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * The service loses all ability to connect to named dependencies. New connections fail immediately;
 * cached connections may survive briefly until the connection pool times out. Operator intervention
 * (restoring DNS) is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>NXDOMAIN cascade is a canonical failure mode described in Google's SRE book ("Testing for
 * Reliability", §Chapter 17) and in the AWS Well-Architected DNS reliability guidance. DNS-related
 * outages account for roughly 10% of major cloud incidents by historical count.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @CompositeChaosNxDomain
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
@Repeatable(CompositeChaosNxDomain.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.dns.testpack.composers.NxDomainComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosNxDomain {

  /**
   * Hostname to target. {@code "*"} (the default) matches all forward lookups. Provide a specific
   * hostname to limit the chaos to one service dependency.
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
    CompositeChaosNxDomain[] value();
  }
}
