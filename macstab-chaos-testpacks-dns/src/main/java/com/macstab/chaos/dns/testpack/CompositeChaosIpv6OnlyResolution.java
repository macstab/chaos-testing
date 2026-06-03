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
 * <p>DNS resolution returns only IPv6 ({@code AAAA}) answers; all IPv4 ({@code A}) entries are
 * stripped from the answer set. Applications that assume IPv4 connectivity — hard-coded AF_INET
 * socket families, IPv4-only JDBC driver defaults, or IPv4-typed {@code InetSocketAddress} literals
 * — will fail to connect even though the target host is fully reachable over IPv6. Tests the
 * application's dual-stack awareness and address-family negotiation logic.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code DnsRule.filterFamily(anyForward(), AddressFamily.INET6)} via libchaos-dns.
 * Post-resolution, every {@code ai_addrinfo} node whose {@code ai_family} is {@code AF_INET} is
 * removed from the result list before it is returned to the caller. In production this happens
 * during IPv6-only network migrations, when an IPv4 NAT gateway fails and only IPv6 paths remain
 * active, or when a dual-stack CDN misconfigures its anycast announcement.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Applications with correct Happy Eyeballs (RFC 8305) or dual-stack logic survive transparently.
 * Applications that bind {@code AF_INET} sockets explicitly, parse IPv4 strings from configuration,
 * or use legacy JDBC URL forms without IPv6 bracket notation will fail immediately. The failure mode
 * is a connect timeout or {@code java.net.ConnectException}, not an obviously DNS-related error.
 *
 * <h2>Industry references</h2>
 *
 * <p>IPv6-only operation is a documented AWS transition milestone (AWS IPv6 migration guide, 2023)
 * and an Apple App Store requirement for iOS apps (WWDC 2015 session 719). Happy Eyeballs
 * algorithm is defined in RFC 8305. The dual-stack failure mode is a known source of latency spikes
 * in Google's internal infrastructure as documented in the SRE book's reliability chapters.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @CompositeChaosIpv6OnlyResolution
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
@Repeatable(CompositeChaosIpv6OnlyResolution.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.dns.testpack.composers.Ipv6OnlyResolutionComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosIpv6OnlyResolution {

  /**
   * Hostname to target. {@code "*"} (the default) applies IPv6-only filtering to all forward
   * lookups. Provide a specific hostname to limit the chaos to one dependency.
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
    CompositeChaosIpv6OnlyResolution[] value();
  }
}
