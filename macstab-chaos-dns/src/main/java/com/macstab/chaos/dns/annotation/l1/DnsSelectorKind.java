/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1;

import com.macstab.chaos.dns.model.DnsSelector;

/**
 * L1-tier projection of the libchaos-dns {@link DnsSelector} hierarchy onto the three stateless
 * "wildcard" forms exposed at the annotation tier. The L1 surface intentionally omits parameterised
 * selectors (exact host, suffix wildcard, IP literal) — for per-host / per-IP targeting the
 * imperative {@code AdvancedDnsChaos} API remains the right entry point.
 *
 * <p>Each enum constant maps to exactly one concrete {@link DnsSelector} instance via {@link
 * #toSelector()}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public enum DnsSelectorKind {

  /** All forward ({@code getaddrinfo}) lookups — wire form {@code dns://*}. */
  FORWARD {
    @Override
    public DnsSelector toSelector() {
      return DnsSelector.anyForward();
    }
  },

  /** All reverse ({@code getnameinfo}) lookups — wire form {@code rdns://*}. */
  REVERSE {
    @Override
    public DnsSelector toSelector() {
      return DnsSelector.anyReverse();
    }
  },

  /** Any resolver call, forward or reverse — wire form {@code *}. */
  WILDCARD {
    @Override
    public DnsSelector toSelector() {
      return DnsSelector.wildcard();
    }
  };

  /** @return the concrete DnsSelector singleton for this kind */
  public abstract DnsSelector toSelector();
}
