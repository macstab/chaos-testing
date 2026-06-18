/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A libchaos-io rule: an effect to apply on a syscall family at a given path prefix.
 *
 * <p>Constructed via the validating static factories ({@link #errno}, {@link #latency}, {@link
 * #torn}, {@link #corrupt}), each of which channels the caller into a known-safe combination of
 * {@link IoOperation} and {@link Effect}. The bare canonical constructor stays accessible but
 * enforces the same op×effect matrix as a defence-in-depth check.
 *
 * <p><strong>Op×effect compatibility matrix</strong> (libchaos-io contract):
 *
 * <table>
 *   <caption>Allowed (✓) and disallowed (✗) effect-on-operation pairings</caption>
 *   <tr><th></th><th>{@code ERRNO}</th><th>{@code LATENCY}</th><th>{@code TORN}</th><th>{@code CORRUPT}</th></tr>
 *   <tr><td>{@link IoOperation#WRITE}, {@link IoOperation#PWRITE}</td><td>✓</td><td>✓</td><td>✓</td><td>✗</td></tr>
 *   <tr><td>{@link IoOperation#READ}, {@link IoOperation#PREAD}</td><td>✓</td><td>✓</td><td>✗</td><td>✓</td></tr>
 *   <tr><td>everything else</td><td>✓</td><td>✓</td><td>✗</td><td>✗</td></tr>
 * </table>
 *
 * <p>The matrix is enforced both here (at Java construction) and again by libchaos-io's config
 * parser at rule-load time — defence in depth so invalid combinations surface as early as possible.
 *
 * @param path path-prefix selector (never {@code null})
 * @param operation syscall operation to intercept (never {@code null})
 * @param effect effect to apply when the rule matches (never {@code null})
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/IO.md">libchaos-io
 *     rule grammar</a>
 */
public record IoRule(PathPrefix path, IoOperation operation, Effect effect) {

  private static final Set<IoOperation> WRITE_OPS =
      EnumSet.of(IoOperation.WRITE, IoOperation.PWRITE);
  private static final Set<IoOperation> READ_OPS = EnumSet.of(IoOperation.READ, IoOperation.PREAD);

  /**
   * Canonical constructor — validates all components and the op×effect compatibility matrix.
   *
   * @throws NullPointerException if any reference component is {@code null}
   * @throws IllegalArgumentException if the {@code (operation, effect)} pair is not allowed by the
   *     libchaos-io grammar
   */
  public IoRule {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(effect, "effect must not be null");
    requireCompatible(operation, effect);
  }

  // ==================== Static factories ====================

  /**
   * Errno-fault rule. Valid on every {@link IoOperation}.
   *
   * @param path path-prefix selector
   * @param operation syscall operation
   * @param errno errno to inject
   * @param probability probability in {@code (0.0, 1.0]}
   * @return new rule
   * @throws NullPointerException if any reference argument is {@code null}
   * @throws IllegalArgumentException if {@code probability} is outside {@code (0.0, 1.0]}
   */
  public static IoRule errno(
      final PathPrefix path,
      final IoOperation operation,
      final Errno errno,
      final double probability) {
    return new IoRule(path, operation, Effect.errno(errno, probability));
  }

  /**
   * Latency rule. Valid on every {@link IoOperation}. The delay is always applied when the rule
   * matches — there is no probability dimension for latency in libchaos-io.
   *
   * @param path path-prefix selector
   * @param operation syscall operation
   * @param delay non-negative latency to inject
   * @return new rule
   */
  public static IoRule latency(
      final PathPrefix path, final IoOperation operation, final Duration delay) {
    return new IoRule(path, operation, Effect.latency(delay));
  }

  /**
   * Torn-write rule. Operation must be {@link IoOperation#WRITE} or {@link IoOperation#PWRITE} —
   * torn-write semantics only apply to write-family syscalls.
   *
   * @param path path-prefix selector
   * @param operation must be {@link IoOperation#WRITE} or {@link IoOperation#PWRITE}
   * @param probability probability in {@code (0.0, 1.0]} that a write is torn
   * @return new rule
   * @throws IllegalArgumentException on non-write operation or invalid probability
   */
  public static IoRule torn(
      final PathPrefix path, final IoOperation operation, final double probability) {
    return new IoRule(path, operation, Effect.torn(probability));
  }

  /**
   * Corrupt-read rule. Operation must be {@link IoOperation#READ} or {@link IoOperation#PREAD} —
   * post-success corruption only applies to read-family syscalls.
   *
   * @param path path-prefix selector
   * @param operation must be {@link IoOperation#READ} or {@link IoOperation#PREAD}
   * @param probability probability in {@code (0.0, 1.0]} that the read buffer is corrupted
   * @return new rule
   * @throws IllegalArgumentException on non-read operation or invalid probability
   */
  public static IoRule corrupt(
      final PathPrefix path, final IoOperation operation, final double probability) {
    return new IoRule(path, operation, Effect.corrupt(probability));
  }

  // ==================== Validation helpers ====================

  private static void requireCompatible(final IoOperation op, final Effect effect) {
    switch (effect) {
      case Effect.ErrnoFault ignored -> {
        // valid on every operation
      }
      case Effect.Latency ignored -> {
        // valid on every operation
      }
      case Effect.Torn ignored -> {
        if (!WRITE_OPS.contains(op)) {
          throw new IllegalArgumentException("TORN is only valid on WRITE and PWRITE, got: " + op);
        }
      }
      case Effect.Corrupt ignored -> {
        if (!READ_OPS.contains(op)) {
          throw new IllegalArgumentException("CORRUPT is only valid on READ and PREAD, got: " + op);
        }
      }
    }
  }
}
