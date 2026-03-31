/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * Parses Docker-compatible resource constraint strings (memory, CPU, disk).
 *
 * <p><strong>Supported Formats:</strong>
 *
 * <ul>
 *   <li><strong>Memory:</strong> {@code "512M"}, {@code "1G"}, {@code "2048K"} (case-insensitive)
 *   <li><strong>CPUs:</strong> {@code "2"}, {@code "0.5"}, {@code "4.0"} (decimal string)
 *   <li><strong>Disk:</strong> {@code "10G"}, {@code "5G"} (gigabytes only, case-insensitive)
 * </ul>
 *
 * <p><strong>Design Principles:</strong>
 *
 * <ul>
 *   <li><strong>Fail fast:</strong> Throws {@link IllegalArgumentException} on invalid input
 *       (before Docker API calls)
 *   <li><strong>Explicit:</strong> No magic defaults, no silent fallbacks
 *   <li><strong>Strict:</strong> Only Docker-compatible formats accepted (no "512MB", no "2.5.5")
 *   <li><strong>Portable:</strong> Works on all platforms (Linux, macOS, Windows)
 *   <li><strong>Stateless:</strong> Pure functions, thread-safe, no side effects
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class ResourceParser {

  private static final Pattern MEMORY_PATTERN =
      Pattern.compile("^[+]?\\d+[KMG]$", Pattern.CASE_INSENSITIVE);
  private static final Pattern CPU_PATTERN = Pattern.compile("^[+]?\\d+(\\.\\d+)?$");
  private static final Pattern DISK_PATTERN =
      Pattern.compile("^[+]?\\d+G$", Pattern.CASE_INSENSITIVE);

  private static final long KB = 1024L;
  private static final long MB = 1024L * KB;
  private static final long GB = 1024L * MB;

  private static final long NANO_CPUS_PER_CPU = 1_000_000_000L;

  private static final long MAX_REASONABLE_MEMORY = 1024L * GB;
  private static final long MAX_REASONABLE_CPUS = 128L;
  private static final long MAX_REASONABLE_DISK_GB = 1024L;

  private ResourceParser() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Parses memory string to bytes.
   *
   * @param input memory string (e.g., "512M")
   * @return memory in bytes
   * @throws IllegalArgumentException if format invalid or value non-positive
   */
  public static long parseMemoryBytes(final String input) {
    if (input == null || input.isBlank()) {
      throw new IllegalArgumentException("Memory string cannot be null or blank");
    }

    final String normalized = input.trim().toUpperCase(Locale.ROOT);

    if (!MEMORY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          String.format("Invalid memory format '%s' (expected '512M', '1G', or '2048K')", input));
    }

    final char suffix = normalized.charAt(normalized.length() - 1);
    final long value = Long.parseLong(normalized.substring(0, normalized.length() - 1));

    if (value <= 0) {
      throw new IllegalArgumentException(
          String.format("Memory must be positive (got '%s')", input));
    }

    final long bytes =
        switch (suffix) {
          case 'K' -> value * KB;
          case 'M' -> value * MB;
          case 'G' -> value * GB;
          default -> throw new IllegalStateException("Regex mismatch: " + suffix);
        };

    if (bytes > MAX_REASONABLE_MEMORY) {
      log.warn("Memory limit unusually high: {} ({} bytes, >1TB)", input, bytes);
    }

    return bytes;
  }

  /**
   * Parses CPU count string to Docker nano-CPUs.
   *
   * @param input CPU count string (e.g., "2", "0.5")
   * @return CPU count in Docker nano-CPUs
   * @throws IllegalArgumentException if format invalid or value non-positive
   */
  public static long parseCpuNanoCpus(final String input) {
    if (input == null || input.isBlank()) {
      throw new IllegalArgumentException("CPU count string cannot be null or blank");
    }

    final String normalized = input.trim();

    if (!CPU_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          String.format("Invalid CPU count '%s' (expected decimal like '2' or '0.5')", input));
    }

    final BigDecimal cpus = new BigDecimal(normalized);

    if (cpus.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException(
          String.format("CPU count must be positive (got '%s')", input));
    }

    final long nanoCpus = cpus.multiply(BigDecimal.valueOf(NANO_CPUS_PER_CPU)).longValue();

    if (cpus.longValue() > MAX_REASONABLE_CPUS) {
      log.warn("CPU count unusually high: {} ({} nano-CPUs, >128 CPUs)", input, nanoCpus);
    }

    return nanoCpus;
  }

  /**
   * Parses disk size string to Docker storage option.
   *
   * @param input disk size string (e.g., "10G")
   * @return Docker storage option (e.g., "size=10G")
   * @throws IllegalArgumentException if format invalid or value non-positive
   */
  public static String parseDiskSizeOption(final String input) {
    if (input == null || input.isBlank()) {
      throw new IllegalArgumentException("Disk size string cannot be null or blank");
    }

    final String normalized = input.trim().toUpperCase(Locale.ROOT);

    if (!DISK_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          String.format("Invalid disk size '%s' (expected gigabytes like '10G' or '5G')", input));
    }

    final long value = Long.parseLong(normalized.substring(0, normalized.length() - 1));

    if (value <= 0) {
      throw new IllegalArgumentException(
          String.format("Disk size must be positive (got '%s')", input));
    }

    if (value > MAX_REASONABLE_DISK_GB) {
      log.warn("Disk size unusually high: {} ({}GB, >1TB)", input, value);
    }

    return "size=" + normalized;
  }
}
