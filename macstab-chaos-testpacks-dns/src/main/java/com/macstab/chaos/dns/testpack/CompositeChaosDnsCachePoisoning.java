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
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Every forward DNS lookup is silently rewritten to resolve {@code redirectTo} instead of the
 * real target. Applications follow the rewritten answer and connect to the wrong host — simulating
 * a poisoned DNS cache, a BGP hijack affecting the authoritative name server, or a compromised
 * split-horizon resolver in a multi-cloud VPC. Traffic intended for a trusted dependency arrives at
 * an uncontrolled endpoint.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code DnsRule.rewrite(anyForward(), redirectTo)} via libchaos-dns. The host name in
 * every {@code getaddrinfo()} call is swapped to {@code redirectTo} before the real resolver is
 * consulted. The caller sees a successful resolution — pointing at the wrong address. In production
 * this happens during BGP route leak events, when a CDN cache is poisoned via a Kaminsky-style
 * attack, or when a misconfigured private DNS zone shadows a public name.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Data exfiltration risk if the target is sensitive. Connection pools warm against the wrong host;
 * health checks pass against the substitute; authentication fails if TLS SNI or certificate pinning
 * is strict. The substitution is invisible to application logs — requests appear to succeed while
 * traffic is misdirected. Recovery requires DNS flush and full container restart.
 *
 * <h2>Industry references</h2>
 *
 * <p>DNS cache poisoning is documented in CISA Advisory AA20-352A and was the root cause of the
 * 2020 Brazilian banking DNS hijack. MITRE ATT&amp;CK technique T1584.002 (DNS server compromise)
 * covers the attacker perspective. The Kaminsky attack (CVE-2008-1447) established the canonical
 * threat model for DNS cache poisoning.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @CompositeChaosDnsCachePoisoning(redirectTo = "localhost")
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
@Repeatable(CompositeChaosDnsCachePoisoning.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.dns.testpack.composers.DnsCachePoisoningComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosDnsCachePoisoning {

  /**
   * Hostname to target. {@code "*"} (the default) rewrites all forward lookups. Provide a specific
   * hostname to target only one dependency.
   */
  String host() default "*";

  /**
   * Replacement hostname that the resolver will be directed to look up instead of the real target.
   * Defaults to {@code "localhost"}.
   */
  String redirectTo() default "localhost";

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-dns.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosDnsCachePoisoning[] value();
  }
}
