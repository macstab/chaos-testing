/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.platform.linux.AlpineLinuxPlatform;
import com.macstab.chaos.core.platform.linux.DebianLinuxPlatform;
import com.macstab.chaos.core.platform.linux.RhelLinuxPlatform;
import com.macstab.chaos.core.platform.linux.UbuntuLinuxPlatform;

import lombok.extern.slf4j.Slf4j;

/**
 * Automatic platform detection for containers.
 *
 * <p>Detects Linux distribution via /etc/os-release or package manager fallback.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class PlatformDetector {

  private PlatformDetector() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Detect platform from running container.
   *
   * @param container container
   * @return platform instance
   * @throws UnsupportedPlatformException if detection fails
   */
  public static Platform detect(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }

    // Strategy 1: Check /etc/os-release (standard on modern Linux)
    final Platform osRelease = detectViaOsRelease(container);
    if (osRelease != null) {
      log.debug("Detected platform via /etc/os-release: {}", osRelease.getDistribution());
      return osRelease;
    }

    // Strategy 2: Fallback to package manager detection
    final Platform packageManager = detectViaPackageManager(container);
    if (packageManager != null) {
      log.debug(
          "Detected platform via package manager: {}", packageManager.getDistribution());
      return packageManager;
    }

    throw new UnsupportedPlatformException(
        "Could not detect platform (tried: /etc/os-release, package managers)");
  }

  private static Platform detectViaOsRelease(final GenericContainer<?> container) {
    try {
      final var result = container.execInContainer("cat", "/etc/os-release");
      if (result.getExitCode() != 0) {
        return null;
      }

      final String osRelease = result.getStdout().toLowerCase();

      if (osRelease.contains("id=debian") || osRelease.contains("id=\"debian\"")) {
        return new DebianLinuxPlatform();
      }
      if (osRelease.contains("id=alpine")) {
        return new AlpineLinuxPlatform();
      }
      if (osRelease.contains("id=ubuntu") || osRelease.contains("id=\"ubuntu\"")) {
        return new UbuntuLinuxPlatform();
      }
      if (osRelease.contains("id=\"rhel\"")
          || osRelease.contains("id=\"centos\"")
          || osRelease.contains("id=\"rocky\"")
          || osRelease.contains("id=\"almalinux\"")) {
        return new RhelLinuxPlatform();
      }

      return null;

    } catch (final Exception e) {
      log.debug("Failed to read /etc/os-release: {}", e.getMessage());
      return null;
    }
  }

  private static Platform detectViaPackageManager(final GenericContainer<?> container) {
    try {
      // Debian/Ubuntu use apt-get
      var result = container.execInContainer("which", "apt-get");
      if (result.getExitCode() == 0) {
        // Distinguish Debian vs Ubuntu by checking lsb_release or VERSION_CODENAME
        try {
          result = container.execInContainer("cat", "/etc/os-release");
          if (result.getExitCode() == 0 && result.getStdout().toLowerCase().contains("ubuntu")) {
            return new UbuntuLinuxPlatform();
          }
        } catch (final Exception ignored) {
          // Fall through to Debian
        }
        return new DebianLinuxPlatform();
      }

      // Alpine uses apk
      result = container.execInContainer("which", "apk");
      if (result.getExitCode() == 0) {
        return new AlpineLinuxPlatform();
      }

      // RHEL/CentOS use yum or dnf
      result = container.execInContainer("which", "dnf");
      if (result.getExitCode() == 0) {
        return new RhelLinuxPlatform();
      }

      result = container.execInContainer("which", "yum");
      if (result.getExitCode() == 0) {
        return new RhelLinuxPlatform();
      }

      return null;

    } catch (final Exception e) {
      log.debug("Failed to detect via package manager: {}", e.getMessage());
      return null;
    }
  }
}
