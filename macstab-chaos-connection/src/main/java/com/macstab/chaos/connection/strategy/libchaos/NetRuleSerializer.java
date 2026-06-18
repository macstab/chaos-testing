/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.strategy.libchaos;

import java.util.Objects;

import com.macstab.chaos.connection.model.NetRule;

/**
 * Renders a {@link NetRule} into the libchaos-net wire format consumed by {@code
 * /tmp/.chaos-net.conf}.
 *
 * <p>Format: {@code <selector>:<operation>:<effect_with_value>:<toxicity>}. The selector and the
 * effect's value can each contain colons (e.g. {@code tcp4://host:port}, {@code
 * ERRNO:ECONNREFUSED}); the libchaos-net parser is field-position aware.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
final class NetRuleSerializer {

  private NetRuleSerializer() {}

  /**
   * @param rule rule to render
   * @return wire-format line, no trailing newline
   * @throws NullPointerException if {@code rule} is {@code null}
   */
  static String serialize(final NetRule rule) {
    Objects.requireNonNull(rule, "rule must not be null");
    return rule.endpoint().toSelector()
        + ":"
        + rule.operation().wireForm()
        + ":"
        + rule.effect().wireForm()
        + ":"
        + Double.toString(rule.toxicity());
  }
}
