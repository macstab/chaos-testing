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
 * <p>Every reverse DNS lookup ({@code getnameinfo()} call) returns {@code EAI_NONAME}. Applications
 * that resolve IP addresses back to hostnames for logging, access-control rule matching, TLS SNI
 * validation, or Kerberos/GSSAPI principal derivation will fail to perform the reverse lookup.
 * Tests whether the application degrades gracefully when PTR records are unavailable — the common
 * production state in cloud VPCs where reverse DNS is not always configured.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code DnsRule.eai(anyReverse(), EAI_NONAME)} via libchaos-dns. Every {@code
 * getnameinfo()} call on the container returns NXDOMAIN regardless of the queried IP address. In
 * production this happens in cloud environments where PTR records are absent for auto-assigned IPs,
 * when a private DNS zone lacks a corresponding reverse zone, or when firewall rules block reverse
 * DNS UDP traffic.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Mild</strong><br>
 * Reverse DNS is advisory in most protocols. Well-written applications fall back to logging the raw
 * IP address. Applications that block on reverse lookup before accepting connections (e.g. legacy
 * SMTP servers with {@code reject_unknown_reverse_client_hostname}) stall or reject connections.
 * Applications that require reverse DNS for access control checks become overly permissive or
 * completely locked, depending on the failure-open/close policy.
 *
 * <h2>Industry references</h2>
 *
 * <p>Reverse DNS unavailability in cloud infrastructure is documented in AWS VPC DNS documentation
 * (custom PTR records require specific configuration) and in GCP Cloud DNS private zone best
 * practices. The SMTP reverse-DNS requirement is described in RFC 5321 §4.1.1.1 and is a known
 * source of false-positive spam rejections in misconfigured environments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @CompositeChaosReverseDnsFailure
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
@Repeatable(CompositeChaosReverseDnsFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.dns.testpack.composers.ReverseDnsFailureComposer",
    severity = Severity.MILD)
public @interface CompositeChaosReverseDnsFailure {

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-dns.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosReverseDnsFailure[] value();
  }
}
