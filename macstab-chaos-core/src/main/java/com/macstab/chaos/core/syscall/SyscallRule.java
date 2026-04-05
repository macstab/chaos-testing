/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

import java.util.Objects;

/**
 * Typed builder for {@code libchaos-io} config rules.
 *
 * <p>Produces config lines in the format expected by the {@code .so}:
 * {@code path:operation:action:parameters}
 *
 * <h2>Usage Examples</h2>
 *
 * <pre>{@code
 * // 30% of writes to /data fail with EIO
 * SyscallRule.errno("/data", "write", "EIO", 0.3).build()
 * // → "/data:write:ERRNO:EIO:0.3"
 *
 * // 200ms latency on all fsync calls to /data/wal.log
 * SyscallRule.latency("/data/wal.log", "fsync", 200).build()
 * // → "/data/wal.log:fsync:LATENCY:200"
 *
 * // 10% of writes are torn (partial)
 * SyscallRule.torn("/data", "write", 0.1).build()
 * // → "/data:write:TORN:0.1"
 *
 * // 5% of reads return corrupted data
 * SyscallRule.corrupt("/data", "read", 0.05).build()
 * // → "/data:read:CORRUPT:0.05"
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SyscallRule {

  private final String rule;

  private SyscallRule(final String rule) {
    this.rule = rule;
  }

  /**
   * Inject an errno failure with configurable probability.
   *
   * @param path        path prefix to match (e.g. "/data", "*" for all)
   * @param operation   syscall name (read, write, open, close, fsync, fdatasync, pread, pwrite)
   * @param errno       error code (EIO, ENOSPC, EDQUOT, EROFS, EACCES, EMFILE, ENOMEM, etc.)
   * @param probability trigger probability 0.0-1.0
   * @return rule builder
   */
  public static SyscallRule errno(
      final String path, final String operation, final String errno, final double probability) {
    validatePath(path);
    validateOperation(operation);
    Objects.requireNonNull(errno, "errno must not be null");
    validateProbability(probability);
    return new SyscallRule(String.format("%s:%s:ERRNO:%s:%.4f", path, operation, errno, probability));
  }

  /**
   * Inject latency (delay) before the syscall executes.
   *
   * @param path      path prefix to match
   * @param operation syscall name
   * @param millis    delay in milliseconds
   * @return rule builder
   */
  public static SyscallRule latency(final String path, final String operation, final long millis) {
    validatePath(path);
    validateOperation(operation);
    if (millis < 0) throw new IllegalArgumentException("millis must be >= 0, got: " + millis);
    return new SyscallRule(String.format("%s:%s:LATENCY:%d", path, operation, millis));
  }

  /**
   * Inject torn (partial) writes — simulates power loss mid-write.
   *
   * @param path        path prefix to match
   * @param operation   must be "write" or "pwrite"
   * @param probability trigger probability 0.0-1.0
   * @return rule builder
   */
  public static SyscallRule torn(
      final String path, final String operation, final double probability) {
    validatePath(path);
    validateOperation(operation);
    validateProbability(probability);
    return new SyscallRule(String.format("%s:%s:TORN:%.4f", path, operation, probability));
  }

  /**
   * Inject data corruption on reads — flips random bits in the returned buffer.
   *
   * @param path        path prefix to match
   * @param operation   must be "read" or "pread"
   * @param probability trigger probability 0.0-1.0
   * @return rule builder
   */
  public static SyscallRule corrupt(
      final String path, final String operation, final double probability) {
    validatePath(path);
    validateOperation(operation);
    validateProbability(probability);
    return new SyscallRule(String.format("%s:%s:CORRUPT:%.4f", path, operation, probability));
  }

  /**
   * Builds the config line string (without owner prefix — added by {@link SyscallFaultInjector}).
   *
   * @return formatted config line
   */
  public String build() {
    return rule;
  }

  @Override
  public String toString() {
    return rule;
  }

  // ==================== Validation ====================

  private static void validatePath(final String path) {
    Objects.requireNonNull(path, "path must not be null");
    if (path.isBlank()) throw new IllegalArgumentException("path must not be blank");
  }

  private static void validateOperation(final String operation) {
    Objects.requireNonNull(operation, "operation must not be null");
    if (operation.isBlank()) throw new IllegalArgumentException("operation must not be blank");
  }

  private static void validateProbability(final double probability) {
    if (probability < 0.0 || probability > 1.0) {
      throw new IllegalArgumentException(
          String.format("probability must be in [0.0, 1.0], got: %.4f", probability));
    }
  }
}
