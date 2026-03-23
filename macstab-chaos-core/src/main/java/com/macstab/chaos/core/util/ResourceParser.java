/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.macstab.chaos.core.exception.ChaosConfigurationException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ResourceParser {

  private static final Pattern RESOURCE_PATTERN = Pattern.compile("^(\\d+)([KMGT])?$");

  private ResourceParser() {
    // Utility class
  }

  /**
   * Parse memory/bandwidth string to bytes.
   *
   * @param resource resource string (e.g., "512M", "1G")
   * @return bytes
   * @throws ChaosConfigurationException if format invalid
   */
  public static long parseBytes(final String resource) {
    Objects.requireNonNull(resource, "resource must not be null");

    final Matcher matcher = RESOURCE_PATTERN.matcher(resource.trim().toUpperCase());
    if (!matcher.matches()) {
      throw new ChaosConfigurationException(
          "Invalid resource format: " + resource + " (expected: 512M, 1G, 2048K)");
    }

    final long value = Long.parseLong(matcher.group(1));
    final String unit = matcher.group(2);

    if (unit == null) {
      return value; // No unit = bytes
    }

    return switch (unit) {
      case "K" -> value * 1024L;
      case "M" -> value * 1024L * 1024L;
      case "G" -> value * 1024L * 1024L * 1024L;
      case "T" -> value * 1024L * 1024L * 1024L * 1024L;
      default -> throw new ChaosConfigurationException("Unsupported unit: " + unit);
    };
  }
}
