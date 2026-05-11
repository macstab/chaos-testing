/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opaque, reified handle for a previously-applied libchaos-io rule.
 *
 * <p>Returned by {@link AdvancedFilesystemChaos#apply} and friends; passed back to {@link
 * AdvancedFilesystemChaos#remove} to surgically remove a single rule without affecting other rules
 * on the same container. The handle's identity is its {@code owner} string — the tag libchaos-io
 * writes into the config file alongside each rule.
 *
 * <p>The {@code owner} format is {@code [a-z0-9_]+}; this constraint matches the libchaos-io
 * grammar enforced by {@code LibchaosTransport}. Callers normally do not construct {@code
 * RuleHandle}s directly — the strategy mints them based on rule contents.
 *
 * <p>This type is module-local — intentionally not shared with {@code
 * com.macstab.chaos.connection.api.RuleHandle}, since the owner-string contract is conceptually
 * independent across modules and cross-module type sharing would create coupling neither module
 * needs.
 *
 * @param owner libchaos-io owner tag, matching {@code [a-z0-9_]+}
 * @author Christian Schnapka - Macstab GmbH
 */
public record RuleHandle(String owner) {

  private static final Pattern OWNER_PATTERN = Pattern.compile("[a-z0-9_]+");

  /**
   * Validates the owner against the libchaos-io safe-character set.
   *
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
