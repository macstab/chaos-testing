/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.strategy.libchaos;

import java.util.Objects;

import com.macstab.chaos.dns.model.DnsRule;

/**
 * Renders a {@link DnsRule} into the libchaos-dns wire format consumed by {@code
 * /tmp/.chaos-dns.conf}.
 *
 * <p>Format: {@code <selector>:<effect_with_value>}. The selector and the effect's value can each
 * contain colons in some grammars (e.g. {@code REWRITE:foo}, {@code dns://example.com}); the
 * libchaos-dns parser is field-position aware.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/DNS.md">libchaos-dns
 *     rule grammar</a>
 */
final class DnsRuleSerializer {

  private DnsRuleSerializer() {}

  /**
   * @param rule rule to render
   * @return wire-format line, no trailing newline
   * @throws NullPointerException if {@code rule} is {@code null}
   */
  static String serialize(final DnsRule rule) {
    Objects.requireNonNull(rule, "rule must not be null");
    return rule.selector().toSelector() + ":" + rule.effect().wireForm();
  }
}
