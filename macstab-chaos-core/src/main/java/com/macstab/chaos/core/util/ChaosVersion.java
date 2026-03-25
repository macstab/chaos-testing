/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

/**
 * Chaos framework version information.
 *
 * <p>Reads version from {@code chaos-version.properties} (generated at build time from {@code
 * gradle.properties}).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ChaosVersion {
  private static final String VERSION;

  static {
    String loadedVersion = "unknown";
    try (final InputStream is =
        ChaosVersion.class.getClassLoader().getResourceAsStream("chaos-version.properties")) {
      if (is != null) {
        final Properties props = new Properties();
        props.load(is);
        loadedVersion = props.getProperty("version", "unknown");
      } else {
        log.warn("chaos-version.properties not found - version will be 'unknown'");
      }
    } catch (final IOException e) {
      log.warn("Failed to load chaos-version.properties", e);
    }
    VERSION = loadedVersion;
  }

  private ChaosVersion() {
    // Utility class
  }

  /**
   * Get current framework version.
   *
   * @return version string (e.g., "1.0.0", "2.1.0-SNAPSHOT")
   */
  public static String get() {
    return VERSION;
  }

  /**
   * Format dependency string for error messages.
   *
   * @param artifactId artifact ID (e.g., "macstab-chaos-cpu")
   * @return formatted Gradle dependency (e.g., {@code
   *     testImplementation("com.macstab.chaos:macstab-chaos-cpu:1.0.0")})
   */
  public static String formatDependency(final String artifactId) {
    return String.format("testImplementation(\"com.macstab.chaos:%s:%s\")", artifactId, VERSION);
  }
}
