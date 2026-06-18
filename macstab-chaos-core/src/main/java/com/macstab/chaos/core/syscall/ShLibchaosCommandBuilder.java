/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * POSIX {@code /bin/sh} implementation of {@link LibchaosCommandBuilder}.
 *
 * <p>Produces commands compatible with both BusyBox (Alpine) and GNU coreutils
 * (Debian/Ubuntu/RHEL). Single quotes inside values are escaped via the standard {@code '\''}
 * closing/escaping/reopening trick.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ShLibchaosCommandBuilder implements LibchaosCommandBuilder {

  /** Owner tag must be regex-safe to prevent {@code sed} pattern injection. */
  private static final Pattern SAFE_OWNER = Pattern.compile("^[a-z0-9_]+$");

  /** Creates a POSIX {@code sh} command builder. */
  public ShLibchaosCommandBuilder() {}

  @Override
  public String buildAppendRule(final String rule, final String owner, final String configPath) {
    Objects.requireNonNull(rule, "rule must not be null");
    validateOwner(owner);
    Objects.requireNonNull(configPath, "configPath must not be null");

    final String tagged = rule + " # " + owner;
    return String.format("printf '%%s\\n' %s >> %s", shellQuote(tagged), configPath);
  }

  @Override
  public String buildAppendRules(
      final List<String> rules, final String owner, final String configPath) {
    Objects.requireNonNull(rules, "rules must not be null");
    validateOwner(owner);
    Objects.requireNonNull(configPath, "configPath must not be null");

    final StringBuilder body = new StringBuilder();
    for (final String rule : rules) {
      Objects.requireNonNull(rule, "rule element must not be null");
      body.append(rule).append(" # ").append(owner).append('\n');
    }
    return String.format("printf '%%s' %s >> %s", shellQuote(body.toString()), configPath);
  }

  @Override
  public String buildRemoveRulesByOwner(final String owner, final String configPath) {
    validateOwner(owner);
    Objects.requireNonNull(configPath, "configPath must not be null");
    // owner is regex-safe due to validateOwner — no escaping required for sed pattern.
    //
    // Defensive form: explicit file-existence guard + unconditional final `true`.
    // The earlier `sed ... 2>/dev/null || true` form was observed to occasionally exit non-zero
    // on busybox sh (Alpine), despite the trailing `|| true`. Splitting the `&&`/`;` chain so
    // the final statement is always `true` guarantees exit 0 regardless of sed's behaviour.
    return String.format(
        "[ -f %s ] && sed -i '/# %s$/d' %s 2>/dev/null; true", configPath, owner, configPath);
  }

  @Override
  public String buildClearAll(final String configPath) {
    Objects.requireNonNull(configPath, "configPath must not be null");
    return "rm -f " + configPath;
  }

  // ==================== Private helpers ====================

  /** Single-quote-escapes a value for safe POSIX {@code sh} interpolation. */
  private static String shellQuote(final String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static void validateOwner(final String owner) {
    Objects.requireNonNull(owner, "owner must not be null");
    if (!SAFE_OWNER.matcher(owner).matches()) {
      throw new IllegalArgumentException("owner must match [a-z0-9_]+, got: '" + owner + "'");
    }
  }
}
