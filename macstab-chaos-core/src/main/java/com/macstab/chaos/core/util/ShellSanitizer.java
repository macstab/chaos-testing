/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.util.Objects;
import java.util.regex.Pattern;

import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * Validates arguments before they are interpolated into shell command strings.
 *
 * <p>Rejects (throws) rather than escapes — for tool names, process names, and package names, shell
 * metacharacters are always invalid. Rejection is safe by construction and avoids the fragility of
 * context-dependent escaping.
 *
 * <p><strong>Allowed characters:</strong> {@code [a-zA-Z0-9._-]} — covers all legitimate tool
 * names, package names, and process names across Debian, Alpine, RHEL, and Fedora.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * ShellSanitizer.validateArgument(processName, "processName");
 * return String.format("kill -9 %s", processName);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ShellSanitizer {

  /** Pattern matching safe shell argument characters. */
  private static final Pattern SAFE_ARGUMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");

  private ShellSanitizer() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Validates that the given value contains only safe characters for shell interpolation.
   *
   * @param value the value to validate
   * @param paramName parameter name for error messages
   * @return the validated value (pass-through for fluent usage)
   * @throws NullPointerException if value or paramName is null
   * @throws ChaosConfigurationException if value is blank or contains unsafe characters
   */
  public static String validateArgument(final String value, final String paramName) {
    Objects.requireNonNull(value, paramName + " must not be null");
    Objects.requireNonNull(paramName, "paramName must not be null");

    if (value.isBlank()) {
      throw new ChaosConfigurationException(paramName + " must not be blank");
    }

    if (!SAFE_ARGUMENT.matcher(value).matches()) {
      throw new ChaosConfigurationException(
          String.format(
              "%s contains unsafe characters for shell interpolation: '%s' "
                  + "(allowed: [a-zA-Z0-9._-])",
              paramName, value));
    }

    return value;
  }
}
