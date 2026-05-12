/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.model;

import java.time.Duration;
import java.util.Objects;

/**
 * A libchaos-process rule: an effect to apply on every interposed call that matches a {@link
 * ProcessSelector}.
 *
 * <p>Constructed via validating static factories ({@link #errno}, {@link #latency}, {@link
 * #failAfter}); the bare canonical constructor stays accessible but enforces the same selector ×
 * errno compatibility matrix as a defence-in-depth check.
 *
 * <p><strong>Why the matrix matters.</strong> libchaos-process itself does not validate errno
 * legality per selector — an invalid combination such as {@code waitpid:ERRNO:EAGAIN} would load
 * but silently no-op at runtime, which is a surprising failure mode. Enforcing the matrix at Java
 * construction gives users a clear error at the call site with the accepted-errno set in the
 * message.
 *
 * <p>The wildcard {@link ProcessSelector#WILDCARD} accepts the union of all per-symbol errno sets —
 * see {@link ProcessSelector} for the rationale.
 *
 * @param selector selector that picks the matching calls (never {@code null})
 * @param effect effect to apply when the rule matches (never {@code null})
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/PROCESS.md">libchaos-process
 *     rule grammar</a>
 */
public record ProcessRule(ProcessSelector selector, ProcessEffect effect) {

  /**
   * Canonical constructor — validates components and the selector × errno compatibility matrix for
   * both {@link ProcessEffect.ErrnoFault} and {@link ProcessEffect.FailAfter}.
   *
   * @throws NullPointerException if any reference component is {@code null}
   * @throws IllegalArgumentException if the {@code (selector, errno)} pair is not in {@link
   *     ProcessSelector#validErrnos()} for the selector
   */
  public ProcessRule {
    Objects.requireNonNull(selector, "selector must not be null");
    Objects.requireNonNull(effect, "effect must not be null");
    requireCompatible(selector, effect);
  }

  // ==================== Static factories ====================

  /** Errno-fault rule with the given probability. */
  public static ProcessRule errno(
      final ProcessSelector selector, final ProcessErrno errno, final double probability) {
    return new ProcessRule(selector, ProcessEffect.errno(errno, probability));
  }

  /** Deterministic errno-fault rule ({@code probability == 1.0}). */
  public static ProcessRule errno(final ProcessSelector selector, final ProcessErrno errno) {
    return new ProcessRule(selector, ProcessEffect.errno(errno));
  }

  /** Latency rule. Valid on every selector. */
  public static ProcessRule latency(final ProcessSelector selector, final Duration delay) {
    return new ProcessRule(selector, ProcessEffect.latency(delay));
  }

  /**
   * Fail-after rule. Let the first {@code count} calls succeed, then fail every subsequent matched
   * call with {@code errno}.
   *
   * @throws IllegalArgumentException if {@code errno} is not valid for {@code selector} or {@code
   *     count < 0}
   */
  public static ProcessRule failAfter(
      final ProcessSelector selector, final ProcessErrno errno, final long count) {
    return new ProcessRule(selector, ProcessEffect.failAfter(errno, count));
  }

  // ==================== Validation helpers ====================

  private static void requireCompatible(
      final ProcessSelector selector, final ProcessEffect effect) {
    final ProcessErrno errno =
        switch (effect) {
          case ProcessEffect.ErrnoFault f -> f.errno();
          case ProcessEffect.FailAfter f -> f.errno();
          case ProcessEffect.Latency ignored -> null;
        };
    if (errno == null) {
      return; // LATENCY: valid on every selector
    }
    if (!selector.accepts(errno)) {
      throw new IllegalArgumentException(
          errno.wireForm()
              + " is not a valid errno for selector "
              + selector.wireForm()
              + ". Accepted errnos: "
              + selector.validErrnos());
    }
  }
}
