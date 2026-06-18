/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A libchaos-dns rule: an effect to apply on every resolver call that matches a selector.
 *
 * <p>Constructed via the validating static factories ({@link #eai}, {@link #latency}, {@link
 * #rewrite}, …), each of which channels the caller into a known-safe combination of {@link
 * DnsSelector} and {@link Effect}. The bare canonical constructor stays accessible but enforces the
 * same selector-kind × effect-kind compatibility matrix as a defence-in-depth check.
 *
 * <p><strong>Compatibility matrix.</strong> Some effects (the post-resolution transforms) only make
 * sense on forward selectors:
 *
 * <table>
 *   <caption>Allowed (✓) and disallowed (✗) effect-on-selector pairings</caption>
 *   <tr><th></th><th>{@code EAI_*}</th><th>{@code LATENCY}</th><th>{@code REWRITE}</th><th>{@code SERVICE}</th><th>{@code OVERRIDE}</th><th>{@code FILTER_FAMILY}</th><th>{@code LIMIT}</th><th>{@code SHUFFLE}</th></tr>
 *   <tr><td>forward {@code dns://...}</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td></tr>
 *   <tr><td>reverse {@code rdns://...}</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✗</td><td>✗</td><td>✗</td><td>✗</td></tr>
 *   <tr><td>wildcard {@code *}</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✗</td><td>✗</td><td>✗</td><td>✗</td></tr>
 * </table>
 *
 * <p>The matrix is enforced both here (at Java construction) and again by libchaos-dns' config
 * parser at rule-load time — defence in depth so invalid combinations surface as early as possible.
 *
 * @param selector selector that picks the matching resolver calls
 * @param effect effect to apply when the rule matches
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/DNS.md">libchaos-dns
 *     rule grammar</a>
 */
public record DnsRule(DnsSelector selector, Effect effect) {

  /**
   * Canonical constructor — validates components and the selector-kind × effect compatibility
   * matrix.
   *
   * @throws NullPointerException if any reference component is {@code null}
   * @throws IllegalArgumentException if a forward-only effect is paired with a reverse or wildcard
   *     selector
   */
  public DnsRule {
    Objects.requireNonNull(selector, "selector must not be null");
    Objects.requireNonNull(effect, "effect must not be null");
    requireCompatible(selector, effect);
  }

  // ==================== Static factories ====================

  /**
   * EAI-fault rule. Valid on every selector kind.
   *
   * @param selector forward, reverse, or wildcard selector
   * @param errno EAI code to inject
   * @return new rule
   */
  public static DnsRule eai(final DnsSelector selector, final EaiErrno errno) {
    return new DnsRule(selector, Effect.eai(errno));
  }

  /**
   * Latency rule. Valid on every selector kind. The delay is always applied when the rule matches —
   * libchaos-dns has no probability dimension for latency.
   *
   * @param selector forward, reverse, or wildcard selector
   * @param delay non-negative latency to inject
   * @return new rule
   */
  public static DnsRule latency(final DnsSelector selector, final Duration delay) {
    return new DnsRule(selector, Effect.latency(delay));
  }

  /**
   * Rewrite the host text. On a forward selector this rewrites the queried name before resolution;
   * on a reverse selector this rewrites the returned host buffer after a successful libc call.
   *
   * @param selector forward, reverse, or wildcard selector
   * @param to replacement host text
   * @return new rule
   */
  public static DnsRule rewrite(final DnsSelector selector, final String to) {
    return new DnsRule(selector, Effect.rewrite(to));
  }

  /**
   * Rewrite the service text. Same forward/reverse semantics as {@link #rewrite}.
   *
   * @param selector forward, reverse, or wildcard selector
   * @param to replacement service text
   * @return new rule
   */
  public static DnsRule service(final DnsSelector selector, final String to) {
    return new DnsRule(selector, Effect.service(to));
  }

  /**
   * Synthesize a fabricated answer list (forward only).
   *
   * @param selector must be a forward selector (otherwise rejected)
   * @param answers non-empty list of synthetic answer addresses
   * @return new rule
   * @throws IllegalArgumentException on reverse/wildcard selector or empty list
   */
  public static DnsRule override(final DnsSelector selector, final List<InetAddress> answers) {
    return new DnsRule(selector, Effect.override(answers));
  }

  /**
   * Retain only result nodes whose family matches (forward only).
   *
   * @param selector must be a forward selector
   * @param family family to retain
   * @return new rule
   */
  public static DnsRule filterFamily(final DnsSelector selector, final AddressFamily family) {
    return new DnsRule(selector, Effect.filterFamily(family));
  }

  /**
   * Keep only the first {@code max} result nodes (forward only).
   *
   * @param selector must be a forward selector
   * @param max maximum number of result nodes ({@code >= 0})
   * @return new rule
   */
  public static DnsRule limit(final DnsSelector selector, final int max) {
    return new DnsRule(selector, Effect.limit(max));
  }

  /**
   * Re-link the result list in random order (forward only).
   *
   * @param selector must be a forward selector
   * @return new rule
   */
  public static DnsRule shuffle(final DnsSelector selector) {
    return new DnsRule(selector, Effect.shuffle());
  }

  // ==================== Validation helpers ====================

  private static void requireCompatible(final DnsSelector selector, final Effect effect) {
    if (!effect.isForwardOnly()) {
      return; // EAI / LATENCY / REWRITE / SERVICE — valid on every selector kind
    }
    if (selector.kind() != DnsSelector.Kind.FORWARD) {
      throw new IllegalArgumentException(
          effect.getClass().getSimpleName().toUpperCase()
              + " is only valid on a forward (dns://...) selector, got selector kind: "
              + selector.kind());
    }
  }
}
