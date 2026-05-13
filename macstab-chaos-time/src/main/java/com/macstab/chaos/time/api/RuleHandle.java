/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opaque handle for a previously-applied libchaos-time rule.
 *
 * <p>Returned by {@link AdvancedTimeChaos#apply} and friends; passed back to {@link
 * AdvancedTimeChaos#remove} to surgically remove a single rule. The handle's identity is its
 * {@code owner} string — the tag libchaos-time writes into the config file alongside each rule.
 *
 * <p>The {@code owner} format is {@code [a-z0-9_]+}; matches the libchaos grammar enforced by
 * {@code LibchaosTransport}. Callers normally do not construct {@code RuleHandle}s directly — the
 * strategy mints them.
 *
 * @param owner libchaos-time owner tag, matching {@code [a-z0-9_]+}
 * @author Christian Schnapka - Macstab GmbH
 */
public record RuleHandle(String owner) {

  private static final Pattern OWNER_PATTERN = Pattern.compile("[a-z0-9_]+");

  /**
   * @throws NullPointerException if {@code owner} is {@code null}
   * @throws IllegalArgumentException if {@code owner} does not match {@code [a-z0-9_]+}
   */
  public RuleHandle {
    Objects.requireNonNull(owner, "owner must not be null");
    if (!OWNER_PATTERN.matcher(owner).matches()) {
      throw new IllegalArgumentException("owner must match [a-z0-9_]+, got: '" + owner + "'");
    }
  }
}
