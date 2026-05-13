/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A libchaos-time rule: an effect to apply on every interposed call that matches a {@link
 * TimeSelector} (optionally narrowed to a single {@link TimeClock}).
 *
 * <p>Constructed via validating static factories ({@link #errno}, {@link #latency}, {@link
 * #offset}); the bare canonical constructor stays accessible but enforces the same selector ×
 * qualifier × effect compatibility matrix as a defence-in-depth check.
 *
 * <p><strong>Why the matrix matters.</strong> libchaos-time's C parser rejects ill-formed
 * combinations (e.g. {@code nanosleep:OFFSET:1000} or {@code clock_gettime/realtime} attached to
 * {@code usleep}) by failing to load the config — a runtime no-op rather than a clear error.
 * Enforcing the matrix at Java construction surfaces invalid combinations at the call site with an
 * accepted-form hint in the message.
 *
 * <p><strong>Rules:</strong>
 *
 * <ul>
 *   <li>A {@link TimeClock} qualifier is permitted <em>only</em> with {@link
 *       TimeSelector#CLOCK_GETTIME}.
 *   <li>The {@link TimeEffect.Offset} effect is permitted <em>only</em> with {@link
 *       TimeSelector#CLOCK_GETTIME} (with or without a clock qualifier).
 * </ul>
 *
 * @param selector base selector that picks the matching calls (never {@code null})
 * @param clock optional per-clock-id qualifier (may only be present when {@code selector ==
 *     CLOCK_GETTIME})
 * @param effect effect to apply when the rule matches (never {@code null})
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/TIME.md">libchaos-time
 *     rule grammar</a>
 */
public record TimeRule(TimeSelector selector, Optional<TimeClock> clock, TimeEffect effect) {

  /**
   * Canonical constructor — validates components and the selector × clock × effect compatibility
   * matrix.
   *
   * @throws NullPointerException if {@code selector}, {@code clock}, or {@code effect} is {@code
   *     null}
   * @throws IllegalArgumentException if {@code clock.isPresent()} for a non-{@code CLOCK_GETTIME}
   *     selector, or if {@code effect} is an {@link TimeEffect.Offset} on a non-{@code
   *     CLOCK_GETTIME} selector
   */
  public TimeRule {
    Objects.requireNonNull(selector, "selector must not be null");
    Objects.requireNonNull(clock, "clock must not be null (use Optional.empty(), never null)");
    Objects.requireNonNull(effect, "effect must not be null");
    requireQualifierCompatible(selector, clock);
    requireEffectCompatible(selector, effect);
  }

  // ==================== Static factories ====================

  /** Errno-fault rule on the unqualified selector. */
  public static TimeRule errno(
      final TimeSelector selector, final TimeErrno errno, final double probability) {
    return new TimeRule(selector, Optional.empty(), TimeEffect.errno(errno, probability));
  }

  /** Deterministic errno-fault rule ({@code probability == 1.0}) on the unqualified selector. */
  public static TimeRule errno(final TimeSelector selector, final TimeErrno errno) {
    return new TimeRule(selector, Optional.empty(), TimeEffect.errno(errno));
  }

  /** Errno-fault rule narrowed to a specific {@link TimeClock} under {@code clock_gettime}. */
  public static TimeRule errno(
      final TimeClock clock, final TimeErrno errno, final double probability) {
    Objects.requireNonNull(clock, "clock must not be null");
    return new TimeRule(
        TimeSelector.CLOCK_GETTIME, Optional.of(clock), TimeEffect.errno(errno, probability));
  }

  /** Latency rule on the unqualified selector. Valid on every selector. */
  public static TimeRule latency(final TimeSelector selector, final Duration delay) {
    return new TimeRule(selector, Optional.empty(), TimeEffect.latency(delay));
  }

  /** Latency rule narrowed to a specific {@link TimeClock} under {@code clock_gettime}. */
  public static TimeRule latency(final TimeClock clock, final Duration delay) {
    Objects.requireNonNull(clock, "clock must not be null");
    return new TimeRule(
        TimeSelector.CLOCK_GETTIME, Optional.of(clock), TimeEffect.latency(delay));
  }

  /**
   * Offset rule on {@code clock_gettime} across every clock.
   *
   * @throws IllegalArgumentException always rejected for selectors other than {@link
   *     TimeSelector#CLOCK_GETTIME} (use the {@link TimeClock} overload for per-clock targeting)
   */
  public static TimeRule offset(final Duration delta) {
    return new TimeRule(TimeSelector.CLOCK_GETTIME, Optional.empty(), TimeEffect.offset(delta));
  }

  /** Offset rule on {@code clock_gettime} across every clock, gated by probability. */
  public static TimeRule offset(final Duration delta, final double probability) {
    return new TimeRule(
        TimeSelector.CLOCK_GETTIME, Optional.empty(), TimeEffect.offset(delta, probability));
  }

  /** Offset rule narrowed to a specific {@link TimeClock} under {@code clock_gettime}. */
  public static TimeRule offset(final TimeClock clock, final Duration delta) {
    Objects.requireNonNull(clock, "clock must not be null");
    return new TimeRule(
        TimeSelector.CLOCK_GETTIME, Optional.of(clock), TimeEffect.offset(delta));
  }

  /**
   * Offset rule narrowed to a specific {@link TimeClock} under {@code clock_gettime}, gated by
   * probability.
   */
  public static TimeRule offset(
      final TimeClock clock, final Duration delta, final double probability) {
    Objects.requireNonNull(clock, "clock must not be null");
    return new TimeRule(
        TimeSelector.CLOCK_GETTIME, Optional.of(clock), TimeEffect.offset(delta, probability));
  }

  // ==================== Validation helpers ====================

  private static void requireQualifierCompatible(
      final TimeSelector selector, final Optional<TimeClock> clock) {
    if (clock.isPresent() && !selector.acceptsClockQualifier()) {
      throw new IllegalArgumentException(
          "clock qualifier '"
              + clock.get().wireForm()
              + "' is not valid for selector "
              + selector.wireForm()
              + "; only CLOCK_GETTIME accepts a TimeClock qualifier.");
    }
  }

  private static void requireEffectCompatible(
      final TimeSelector selector, final TimeEffect effect) {
    if (effect instanceof TimeEffect.Offset && !selector.acceptsOffset()) {
      throw new IllegalArgumentException(
          "OFFSET is not a valid effect for selector "
              + selector.wireForm()
              + "; OFFSET modifies the returned struct timespec and is only meaningful on "
              + "clock_gettime (with or without a clock qualifier).");
    }
  }
}
