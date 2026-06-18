/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.strategy.libchaos;

import java.util.Objects;

import com.macstab.chaos.process.model.ProcessRule;

/**
 * Renders a {@link ProcessRule} into the libchaos-process wire format consumed by {@code
 * /tmp/.chaos-process.conf}.
 *
 * <p>Format: {@code <selector>:<effect-with-value>[@<probability>]}. The {@code @<probability>}
 * suffix on {@code ERRNO} rules is omitted when probability equals {@code 1.0} (libchaos-process
 * defaults missing suffix to {@code 1.0} per {@code PROCESS.md} §10).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
final class ProcessRuleSerializer {

  private ProcessRuleSerializer() {}

  /**
   * @param rule rule to render
   * @return wire-format line, no trailing newline
   * @throws NullPointerException if {@code rule} is {@code null}
   */
  static String serialize(final ProcessRule rule) {
    Objects.requireNonNull(rule, "rule must not be null");
    return rule.selector().wireForm() + ":" + rule.effect().wireForm();
  }
}
