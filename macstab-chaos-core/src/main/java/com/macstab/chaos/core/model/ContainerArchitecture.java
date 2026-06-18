/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.model;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

/**
 * Container CPU architecture detection and mapping.
 *
 * <p>Maps uname -m output to standard architecture names used by binary distributions.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public enum ContainerArchitecture {
  /** ARM 64-bit (aarch64, arm64). */
  ARM64("arm64", "aarch64"),

  /** AMD/Intel 64-bit (x86_64, amd64). */
  AMD64("amd64", "x86_64");

  private final String binaryName;
  private final String unameAlias;

  ContainerArchitecture(final String binaryName, final String unameAlias) {
    this.binaryName = binaryName;
    this.unameAlias = unameAlias;
  }

  /**
   * Returns binary distribution name (arm64, amd64).
   *
   * @return architecture name for binary downloads
   */
  public String getBinaryName() {
    return binaryName;
  }

  /**
   * Returns uname -m alias (aarch64, x86_64).
   *
   * @return alternative architecture name from uname
   */
  public String getUnameAlias() {
    return unameAlias;
  }

  /**
   * Detects container architecture via uname -m.
   *
   * @param container target container (must be running)
   * @return detected architecture
   * @throws IllegalStateException if detection fails
   * @throws NullPointerException if container is null
   */
  public static ContainerArchitecture detect(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    try {
      final var result = container.execInContainer("uname", "-m");
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            "Failed to detect architecture: uname -m exited with code " + result.getExitCode());
      }

      final String unameOutput = result.getStdout().trim().toLowerCase();

      if (unameOutput.contains("aarch64") || unameOutput.contains("arm64")) {
        return ARM64;
      }

      if (unameOutput.contains("x86_64") || unameOutput.contains("amd64")) {
        return AMD64;
      }

      throw new IllegalStateException(
          "Unsupported architecture: " + unameOutput + " (expected: aarch64/arm64/x86_64/amd64)");

    } catch (final Exception e) {
      if (e instanceof IllegalStateException) {
        throw (IllegalStateException) e;
      }
      throw new IllegalStateException("Failed to detect container architecture", e);
    }
  }
}
