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
 * <p>The DNS resolver returns {@code EAI_AGAIN} (SERVFAIL — "try again later") for forward
 * lookups. Unlike NXDOMAIN ({@link CompositeChaosNxDomain}), {@code EAI_AGAIN} is the retryable
 * variant: well-written clients retry on SERVFAIL, so this scenario tests whether the retry logic
 * is correctly implemented and whether the application handles DNS flap without cascading. Simulates
 * a temporarily overloaded or flapping DNS resolver.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code DnsRule.eai(anyForward(), EAI_AGAIN)} via libchaos-dns. Every {@code
 * getaddrinfo()} call returns SERVFAIL until the rule is removed. In production this happens when
 * an upstream recursive resolver is overwhelmed, when a DNSSEC validation chain breaks temporarily,
 * or during rolling restarts of a self-hosted bind/unbound cluster.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Services with correct retry logic and cached connections typically survive. Services that eagerly
 * open new connections per request fail until their DNS TTL cache has a warm entry. The key
 * resilience property tested: does the service retry, and does it not amplify retries into a
 * thundering herd on the name server?
 *
 * <h2>Industry references</h2>
 *
 * <p>SERVFAIL retry behaviour is a known correctness concern documented in RFC 8767 ("Serving Stale
 * Data to Improve DNS Resiliency") and in the AWS Route 53 resolver health-check guidelines.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @CompositeChaosDnsTemporaryFailure
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
@Repeatable(CompositeChaosDnsTemporaryFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.dns.testpack.composers.DnsTemporaryFailureComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosDnsTemporaryFailure {

  /**
   * Hostname to target. {@code "*"} (the default) matches all forward lookups.
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
    CompositeChaosDnsTemporaryFailure[] value();
  }
}
