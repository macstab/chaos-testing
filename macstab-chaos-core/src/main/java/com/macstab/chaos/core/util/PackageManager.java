/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.util.Arrays;
import java.util.List;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

/**
 * Represents a Linux package manager with installation capabilities.
 *
 * <p><strong>Supported Package Managers:</strong>
 *
 * <ul>
 *   <li><strong>APT</strong> - Debian, Ubuntu (apt-get)
 *   <li><strong>APK</strong> - Alpine Linux (apk)
 *   <li><strong>DNF</strong> - Fedora, RHEL 8+, CentOS 8+ (dnf)
 *   <li><strong>YUM</strong> - CentOS 7, RHEL 7 (yum)
 *   <li><strong>PACMAN</strong> - Arch Linux (pacman)
 *   <li><strong>ZYPPER</strong> - openSUSE (zypper)
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Enum is thread-safe and immutable.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public enum PackageManager {
  /**
   * APT package manager (Debian, Ubuntu).
   *
   * <p>Command: {@code apt-get}
   */
  APT("apt-get", "debian", "ubuntu") {
    @Override
    public void install(final GenericContainer<?> container, final String... packages)
        throws Exception {
      // Update package list
      final var updateResult = container.execInContainer("apt-get", "update");
      checkExitCode(updateResult, "apt-get update");

      // Install packages
      final List<String> command =
          buildCommand("apt-get", "install", "-y", "--no-install-recommends", packages);
      final var installResult = container.execInContainer(command.toArray(new String[0]));
      checkExitCode(installResult, "apt-get install");
    }

    @Override
    public boolean isAvailable(final GenericContainer<?> container) {
      try {
        final var result = container.execInContainer("which", "apt-get");
        return result.getExitCode() == 0;
      } catch (Exception e) {
        return false;
      }
    }
  },

  /**
   * APK package manager (Alpine Linux).
   *
   * <p>Command: {@code apk}
   */
  APK("apk", "alpine") {
    @Override
    public void install(final GenericContainer<?> container, final String... packages)
        throws Exception {
      // Update package index
      final var updateResult = container.execInContainer("apk", "update");
      checkExitCode(updateResult, "apk update");

      // Install packages
      final List<String> command = buildCommand("apk", "add", "--no-cache", packages);
      final var installResult = container.execInContainer(command.toArray(new String[0]));
      checkExitCode(installResult, "apk add");
    }

    @Override
    public boolean isAvailable(final GenericContainer<?> container) {
      try {
        final var result = container.execInContainer("which", "apk");
        return result.getExitCode() == 0;
      } catch (Exception e) {
        return false;
      }
    }
  },

  /**
   * DNF package manager (Fedora, RHEL 8+, CentOS 8+).
   *
   * <p>Command: {@code dnf}
   */
  DNF("dnf", "fedora", "rhel", "centos") {
    @Override
    public void install(final GenericContainer<?> container, final String... packages)
        throws Exception {
      // DNF doesn't require explicit update before install
      final List<String> command = buildCommand("dnf", "install", "-y", packages);
      final var installResult = container.execInContainer(command.toArray(new String[0]));
      checkExitCode(installResult, "dnf install");
    }

    @Override
    public boolean isAvailable(final GenericContainer<?> container) {
      try {
        final var result = container.execInContainer("which", "dnf");
        return result.getExitCode() == 0;
      } catch (Exception e) {
        return false;
      }
    }
  },

  /**
   * YUM package manager (CentOS 7, RHEL 7).
   *
   * <p>Command: {@code yum}
   */
  YUM("yum", "centos", "rhel") {
    @Override
    public void install(final GenericContainer<?> container, final String... packages)
        throws Exception {
      // YUM doesn't require explicit update before install
      final List<String> command = buildCommand("yum", "install", "-y", packages);
      final var installResult = container.execInContainer(command.toArray(new String[0]));
      checkExitCode(installResult, "yum install");
    }

    @Override
    public boolean isAvailable(final GenericContainer<?> container) {
      try {
        final var result = container.execInContainer("which", "yum");
        return result.getExitCode() == 0;
      } catch (Exception e) {
        return false;
      }
    }
  },

  /**
   * Pacman package manager (Arch Linux).
   *
   * <p>Command: {@code pacman}
   */
  PACMAN("pacman", "arch") {
    @Override
    public void install(final GenericContainer<?> container, final String... packages)
        throws Exception {
      // Update package database
      final var updateResult = container.execInContainer("pacman", "-Sy");
      checkExitCode(updateResult, "pacman -Sy");

      // Install packages
      final List<String> command = buildCommand("pacman", "-S", "--noconfirm", packages);
      final var installResult = container.execInContainer(command.toArray(new String[0]));
      checkExitCode(installResult, "pacman -S");
    }

    @Override
    public boolean isAvailable(final GenericContainer<?> container) {
      try {
        final var result = container.execInContainer("which", "pacman");
        return result.getExitCode() == 0;
      } catch (Exception e) {
        return false;
      }
    }
  },

  /**
   * Zypper package manager (openSUSE).
   *
   * <p>Command: {@code zypper}
   */
  ZYPPER("zypper", "opensuse", "sles") {
    @Override
    public void install(final GenericContainer<?> container, final String... packages)
        throws Exception {
      // Refresh repositories
      final var refreshResult = container.execInContainer("zypper", "refresh");
      checkExitCode(refreshResult, "zypper refresh");

      // Install packages
      final List<String> command = buildCommand("zypper", "install", "-y", packages);
      final var installResult = container.execInContainer(command.toArray(new String[0]));
      checkExitCode(installResult, "zypper install");
    }

    @Override
    public boolean isAvailable(final GenericContainer<?> container) {
      try {
        final var result = container.execInContainer("which", "zypper");
        return result.getExitCode() == 0;
      } catch (Exception e) {
        return false;
      }
    }
  };

  private final String command;
  private final List<String> distributions;

  PackageManager(final String command, final String... distributions) {
    this.command = command;
    this.distributions = Arrays.asList(distributions);
  }

  /**
   * Gets the package manager command.
   *
   * @return command name (e.g., "apt-get", "apk")
   */
  public String getCommand() {
    return command;
  }

  /**
   * Gets Linux distributions that typically use this package manager.
   *
   * @return distribution names
   */
  public List<String> getDistributions() {
    return distributions;
  }

  /**
   * Installs packages using this package manager.
   *
   * @param container the container to install packages in
   * @param packages package names to install
   * @throws Exception if installation fails
   */
  public abstract void install(GenericContainer<?> container, String... packages) throws Exception;

  /**
   * Checks if this package manager is available in the container.
   *
   * @param container the container to check
   * @return {@code true} if package manager is available
   */
  public abstract boolean isAvailable(GenericContainer<?> container);

  /**
   * Detects the package manager in a container.
   *
   * <p><strong>Detection Strategy:</strong>
   *
   * <ol>
   *   <li>Read /etc/os-release for distribution ID
   *   <li>Match ID to known package managers
   *   <li>Fall back to checking which package managers are available
   * </ol>
   *
   * @param container the container to detect package manager in
   * @return detected package manager
   * @throws IllegalStateException if no package manager is detected
   */
  public static PackageManager detect(final GenericContainer<?> container) {
    try {
      // Try to read /etc/os-release (standard on modern Linux)
      final var result = container.execInContainer("cat", "/etc/os-release");
      if (result.getExitCode() == 0) {
        final String osRelease = result.getStdout().toLowerCase();

        // Extract ID= field
        for (final String line : osRelease.split("\n")) {
          if (line.startsWith("id=")) {
            final String distro = line.substring(3).replaceAll("\"", "").trim();

            // Match to package manager
            for (final PackageManager pm : values()) {
              if (pm.distributions.contains(distro)) {
                return pm;
              }
            }
          }
        }
      }
    } catch (Exception e) {
      // Fall through to availability check
    }

    // Fall back: check which package managers are available
    for (final PackageManager pm : values()) {
      if (pm.isAvailable(container)) {
        return pm;
      }
    }

    throw new IllegalStateException(
        "Could not detect package manager in container " + container.getContainerId());
  }

  /**
   * Builds install command with packages.
   *
   * @param baseCommand base command parts
   * @param packages packages to install
   * @return complete command
   */
  private static List<String> buildCommand(final String... baseCommand) {
    return Arrays.asList(baseCommand);
  }

  /**
   * Builds install command with packages.
   *
   * @param cmd1 command part 1
   * @param cmd2 command part 2
   * @param cmd3 command part 3
   * @param packages packages to install
   * @return complete command
   */
  private static List<String> buildCommand(
      final String cmd1, final String cmd2, final String cmd3, final String... packages) {
    final var command = new java.util.ArrayList<String>();
    command.add(cmd1);
    command.add(cmd2);
    command.add(cmd3);
    command.addAll(Arrays.asList(packages));
    return command;
  }

  /**
   * Builds install command with packages.
   *
   * @param cmd1 command part 1
   * @param cmd2 command part 2
   * @param cmd3 command part 3
   * @param cmd4 command part 4
   * @param packages packages to install
   * @return complete command
   */
  private static List<String> buildCommand(
      final String cmd1,
      final String cmd2,
      final String cmd3,
      final String cmd4,
      final String... packages) {
    final var command = new java.util.ArrayList<String>();
    command.add(cmd1);
    command.add(cmd2);
    command.add(cmd3);
    command.add(cmd4);
    command.addAll(Arrays.asList(packages));
    return command;
  }

  /**
   * Checks command exit code and throws exception if failed.
   *
   * @param result command result
   * @param operation operation description
   * @throws RuntimeException if exit code is not 0
   */
  private static void checkExitCode(final Container.ExecResult result, final String operation) {
    if (result.getExitCode() != 0) {
      throw new RuntimeException(
          operation
              + " failed with exit code "
              + result.getExitCode()
              + "\nstdout: "
              + result.getStdout()
              + "\nstderr: "
              + result.getStderr());
    }
  }
}
