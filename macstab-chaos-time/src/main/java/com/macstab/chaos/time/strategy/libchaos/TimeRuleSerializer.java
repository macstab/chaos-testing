/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.strategy.libchaos;

import java.util.Objects;

import com.macstab.chaos.time.model.TimeRule;

/**
 * Renders a {@link TimeRule} into the libchaos-time wire format consumed by {@code
 * /tmp/.chaos-time.conf}.
 *
 * <p>Format: {@code <selector>[/<clock-id>]:<effect-with-value>[@<probability>]}. The optional
 * {@code /<clock-id>} suffix is rendered only when the rule carries a {@link
 * com.macstab.chaos.time.model.TimeClock} qualifier (which is only possible on {@code
 * clock_gettime}). The {@code @<probability>} suffix is omitted when probability equals {@code
 * 1.0} (libchaos-time defaults a missing suffix to {@code 1.0} per {@code TIME.md} §rule grammar).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
final class TimeRuleSerializer {

  private TimeRuleSerializer() {}

  /**
   * @param rule rule to render
   * @return wire-format line, no trailing newline
   * @throws NullPointerException if {@code rule} is {@code null}
   */
  static String serialize(final TimeRule rule) {
    Objects.requireNonNull(rule, "rule must not be null");
    final String selectorToken =
        rule.clock()
            .map(c -> rule.selector().wireForm() + "/" + c.wireForm())
            .orElseGet(() -> rule.selector().wireForm());
    return selectorToken + ":" + rule.effect().wireForm();
  }
}
