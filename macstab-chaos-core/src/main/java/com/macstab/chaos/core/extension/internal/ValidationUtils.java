/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension.internal;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validation utilities for core annotations.
 *
 * <p><strong>INTERNAL USE ONLY</strong> - Not part of public API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ValidationUtils {

  private static final Pattern MEMORY_PATTERN = Pattern.compile("^\\d+[KMG]$");
  private static final Pattern DISK_PATTERN = Pattern.compile("^\\d+[GT]$");

  private ValidationUtils() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Validate package name is not null, not empty, and contains only valid characters.
   *
   * @param packageName package name
   * @throws IllegalArgumentException if invalid
   */
  public static void validatePackageName(final String packageName) {
    Objects.requireNonNull(packageName, "Package name must not be null");

    if (packageName.trim().isEmpty()) {
      throw new IllegalArgumentException("Package name must not be empty");
    }

    // Package names should only contain: a-z, A-Z, 0-9, dash, underscore, dot
    if (!packageName.matches("[a-zA-Z0-9._-]+")) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid package name: '%s'. Package names must contain only alphanumeric characters, dash, underscore, or dot.",
              packageName));
    }
  }

  /**
   * Validate memory format (e.g., "512M", "1G").
   *
   * @param memory memory string
   * @throws IllegalArgumentException if invalid format
   */
  public static void validateMemoryFormat(final String memory) {
    Objects.requireNonNull(memory, "Memory must not be null");

    if (!MEMORY_PATTERN.matcher(memory).matches()) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid memory format: '%s'. Expected format: <number><unit> (e.g., 512M, 1G)",
              memory));
    }
  }

  /**
   * Validate disk size format (e.g., "10G", "1T").
   *
   * @param diskSize disk size string
   * @throws IllegalArgumentException if invalid format
   */
  public static void validateDiskSizeFormat(final String diskSize) {
    Objects.requireNonNull(diskSize, "Disk size must not be null");

    if (!DISK_PATTERN.matcher(diskSize).matches()) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid disk size format: '%s'. Expected format: <number><unit> (e.g., 10G, 1T)",
              diskSize));
    }
  }

  /**
   * Validate CPU count is positive.
   *
   * @param cpus CPU count
   * @throws IllegalArgumentException if invalid
   */
  public static void validateCpuCount(final int cpus) {
    if (cpus <= 0) {
      throw new IllegalArgumentException(
          String.format("CPU count must be positive, got: %d", cpus));
    }
  }

  /**
   * Validate CPU shares are in valid range (1-262144).
   *
   * <p>Docker allows CPU shares from 2 to 262144, but we allow 1+ for simplicity.
   *
   * @param cpuShares CPU shares
   * @throws IllegalArgumentException if invalid
   */
  public static void validateCpuShares(final int cpuShares) {
    if (cpuShares <= 0) {
      throw new IllegalArgumentException(
          String.format("CPU shares must be positive, got: %d", cpuShares));
    }

    if (cpuShares > 262144) {
      throw new IllegalArgumentException(
          String.format("CPU shares must be <= 262144, got: %d", cpuShares));
    }
  }
}
