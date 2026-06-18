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
 * <p>The address list returned by every forward DNS lookup is re-linked in random order before
 * being returned to the caller. Applications that assume the first answer is always the canonical
 * or lowest-latency address — or that assume answer ordering is stable across lookups — receive a
 * different first address on each query. Tests DNS-aware load-balancing strategies, connection-pool
 * stickiness, and address-ordering assumptions baked into application configuration.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code DnsRule.shuffle(anyForward())} via libchaos-dns. Each {@code getaddrinfo()}
 * call has its result list re-ordered in random order by the interposer before the list pointer is
 * returned to the caller. In production this is the normal behaviour of most authoritative DNS
 * servers (round-robin DNS), AWS Route 53 weighted routing, and any resolver that implements RFC
 * 1794 address record shuffling.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Mild</strong><br>
 * Well-written clients are unaffected: they try the first address, retry on failure. Applications
 * that cache the first IP from a query for the lifetime of the process, or that do naïve first-IP-
 * wins load balancing, will exhibit skewed traffic distribution. This is rarely catastrophic but
 * can expose subtle bugs in connection-pool pinning and multi-homed service discovery.
 *
 * <h2>Industry references</h2>
 *
 * <p>DNS answer reordering is standardised in RFC 1794 ("DNS Support for Load Balancing") and is
 * the basis of round-robin DNS used by major CDNs. AWS Route 53 and Azure Traffic Manager both
 * implement weighted answer shuffling. The Happy Eyeballs algorithm (RFC 8305) is designed to work
 * correctly regardless of answer order.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @CompositeChaosShuffledAnswerOrder
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
@Repeatable(CompositeChaosShuffledAnswerOrder.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.dns.testpack.composers.ShuffledAnswerOrderComposer",
    severity = Severity.MILD)
public @interface CompositeChaosShuffledAnswerOrder {

  /**
   * Hostname to target. {@code "*"} (the default) shuffles answers for all forward lookups. Provide
   * a specific hostname to limit the chaos to one dependency.
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
    CompositeChaosShuffledAnswerOrder[] value();
  }
}
