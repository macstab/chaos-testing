/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility for formatting Docker container IDs in logs and error messages.
 *
 * <p>Docker container IDs are 64-character hex strings. The first 12 characters are sufficient for
 * uniqueness and match Docker CLI conventions ({@code docker ps} shows 12-char IDs by default).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ContainerIdFormatter {

  /**
   * Default truncation length (matches Docker CLI convention).
   *
   * <p>Docker CLI uses 12 characters by default in {@code docker ps} output.
   */
  private static final int DEFAULT_TRUNCATION_LENGTH = 12;

  /** Private constructor to prevent instantiation. */
  private ContainerIdFormatter() {
    throw new UnsupportedOperationException(
        "ContainerIdFormatter is a utility class and cannot be instantiated");
  }

  /**
   * Truncates a Docker container ID to the first 12 characters for logging.
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>{@code "06c27a7ed60269392f23dac224bc3eb7f5be70abcc77b25fd10a004bacf099bb"} → {@code
   *       "06c27a7ed602"}
   *   <li>{@code "abc123"} → {@code "abc123"} (unchanged if already short)
   *   <li>{@code null} → {@code null} (null-safe)
   * </ul>
   *
   * @param containerId the full container ID (may be null)
   * @return the truncated ID (first 12 chars), or the original ID if shorter than 12 chars, or null
   *     if input is null
   */
  public static String truncate(final String containerId) {
    return truncate(containerId, DEFAULT_TRUNCATION_LENGTH);
  }

  /**
   * Truncates a Docker container ID to the specified length for logging.
   *
   * <p><strong>Use Cases:</strong>
   *
   * <ul>
   *   <li>Default 12 chars: {@code truncate(id, 12)} - matches Docker CLI
   *   <li>Short 8 chars: {@code truncate(id, 8)} - very compact logs
   *   <li>Long 16 chars: {@code truncate(id, 16)} - more uniqueness in large clusters
   * </ul>
   *
   * @param containerId the full container ID (may be null)
   * @param length the truncation length (must be positive)
   * @return the truncated ID, or the original ID if shorter than length, or null if input is null
   * @throws IllegalArgumentException if length is not positive
   */
  public static String truncate(final String containerId, final int length) {
    if (length <= 0) {
      throw new IllegalArgumentException("Truncation length must be positive: " + length);
    }

    if (containerId == null) {
      return null;
    }

    return containerId.length() > length ? containerId.substring(0, length) : containerId;
  }

  /**
   * Checks if a container ID is truncated (shorter than standard 64-char Docker ID).
   *
   * <p><strong>Use Case:</strong> Validation before passing IDs to Docker API (which requires full
   * IDs).
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>{@code "06c27a7ed602"} → {@code true} (12 chars, truncated)
   *   <li>{@code "06c27a7ed60269392f23dac224bc3eb7f5be70abcc77b25fd10a004bacf099bb"} → {@code
   *       false} (64 chars, full ID)
   * </ul>
   *
   * @param containerId the container ID to check (may be null)
   * @return true if ID is truncated (shorter than 64 chars), false otherwise (null returns false)
   */
  public static boolean isTruncated(final String containerId) {
    if (containerId == null) {
      return false;
    }
    return containerId.length() < 64;
  }
}
