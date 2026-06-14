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
 * <p>The DNS resolver returns {@code EAI_FAIL} (hard non-retryable failure) for all forward
 * lookups. This is the permanent-failure variant: unlike SERVFAIL ({@link
 * CompositeChaosDnsTemporaryFailure}), {@code EAI_FAIL} signals that the failure is definitive and
 * clients should not retry. Applications that do not distinguish retryable from non-retryable DNS
 * errors will retry uselessly, burning CPU and potentially causing a retry storm.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code DnsRule.eai(anyForward(), EAI_FAIL)} via libchaos-dns. In production this
 * happens when a firewall policy silently drops DNS queries (no UDP response), when a split-horizon
 * resolver returns REFUSED, or when a newly deployed security policy blocks the container's egress.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Identical impact to NXDOMAIN for well-written clients; worse than NXDOMAIN for clients that retry
 * SERVFAIL but not EAI_FAIL — those clients fail immediately without any retry. Operator
 * intervention required.
 *
 * <h2>Industry references</h2>
 *
 * <p>EAI_FAIL as a blackhole indicator is described in the POSIX resolver spec (getaddrinfo(3)).
 * DNS blackholing via firewall drops is a documented attack vector in CISA DNS security advisories.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @CompositeChaosDnsBlackhole
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
@Repeatable(CompositeChaosDnsBlackhole.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.dns.testpack.composers.DnsBlackholeComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosDnsBlackhole {

  /** Hostname to target. {@code "*"} (the default) matches all forward lookups. */
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
    CompositeChaosDnsBlackhole[] value();
  }
}
