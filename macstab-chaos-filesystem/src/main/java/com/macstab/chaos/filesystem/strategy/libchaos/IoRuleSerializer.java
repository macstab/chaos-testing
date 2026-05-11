/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.strategy.libchaos;

import java.util.Objects;

import com.macstab.chaos.filesystem.model.IoRule;

/**
 * Renders an {@link IoRule} into the libchaos-io wire format consumed by {@code
 * /tmp/.chaos-io.conf}.
 *
 * <p>Format: {@code <path-prefix>:<operation>:<errno|action>:<value>}. The path prefix and the
 * effect's value can each contain colons in some grammars, but libchaos-io's parser is
 * field-position aware; {@link com.macstab.chaos.filesystem.model.PathPrefix.AbsolutePath} rejects
 * paths containing {@code ':'} at construction time as defence in depth.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/IO.md">libchaos-io
 *     rule grammar</a>
 */
final class IoRuleSerializer {

  private IoRuleSerializer() {}

  /**
   * @param rule rule to render
   * @return wire-format line, no trailing newline
   * @throws NullPointerException if {@code rule} is {@code null}
   */
  static String serialize(final IoRule rule) {
    Objects.requireNonNull(rule, "rule must not be null");
    return rule.path().toSelector()
        + ":"
        + rule.operation().wireForm()
        + ":"
        + rule.effect().wireForm();
  }
}
