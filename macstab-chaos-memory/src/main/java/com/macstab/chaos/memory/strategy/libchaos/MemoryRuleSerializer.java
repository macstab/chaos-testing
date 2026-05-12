/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.strategy.libchaos;

import java.util.Objects;

import com.macstab.chaos.memory.model.MemoryRule;

/**
 * Renders a {@link MemoryRule} into the libchaos-memory wire format consumed by {@code
 * /tmp/.chaos-memory.conf}.
 *
 * <p>Format: {@code <selector>:<effect-with-value>[@<probability>]}. The {@code @<probability>}
 * suffix is appended by {@link com.macstab.chaos.memory.model.MemoryEffect.ErrnoFault#wireForm()}
 * itself (and omitted when probability equals {@code 1.0}, since the libchaos-memory parser
 * defaults to {@code 1.0} when the suffix is absent — see {@code MEMORY.md} §3.1).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/MEMORY.md">libchaos-memory
 *     rule grammar</a>
 */
final class MemoryRuleSerializer {

  private MemoryRuleSerializer() {}

  /**
   * @param rule rule to render
   * @return wire-format line, no trailing newline
   * @throws NullPointerException if {@code rule} is {@code null}
   */
  static String serialize(final MemoryRule rule) {
    Objects.requireNonNull(rule, "rule must not be null");
    return rule.selector().wireForm() + ":" + rule.effect().wireForm();
  }
}
