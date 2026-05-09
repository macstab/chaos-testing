/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

import java.util.List;

/**
 * Platform-shell command builder for managing the libchaos config file inside a container.
 *
 * <p>Mirrors the {@link com.macstab.chaos.core.command.process.ProcessCommandBuilder} pattern —
 * separates command construction from execution. Implementations encapsulate quoting, escaping, and
 * shell-dialect concerns.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@link ShLibchaosCommandBuilder} — POSIX {@code /bin/sh} (BusyBox + bash compatible)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface LibchaosCommandBuilder {

  /**
   * Build command appending a single rule with owner tag to the config file.
   *
   * @param rule libchaos rule body, e.g. {@code "/data:write:EIO:0.3"}
   * @param owner owner tag (must match {@code [a-z0-9_]+})
   * @param configPath absolute path of the config file in the container
   * @return shell command string
   */
  String buildAppendRule(String rule, String owner, String configPath);

  /**
   * Build command appending multiple rules with the same owner tag in a single exec.
   *
   * @param rules libchaos rule bodies
   * @param owner owner tag (must match {@code [a-z0-9_]+})
   * @param configPath absolute path of the config file in the container
   * @return shell command string
   */
  String buildAppendRules(List<String> rules, String owner, String configPath);

  /**
   * Build command removing all rules tagged with the given owner.
   *
   * @param owner owner tag whose lines should be removed (must match {@code [a-z0-9_]+})
   * @param configPath absolute path of the config file in the container
   * @return shell command string
   */
  String buildRemoveRulesByOwner(String owner, String configPath);

  /**
   * Build command clearing the entire config (removes the config file).
   *
   * @param configPath absolute path of the config file in the container
   * @return shell command string
   */
  String buildClearAll(String configPath);
}
