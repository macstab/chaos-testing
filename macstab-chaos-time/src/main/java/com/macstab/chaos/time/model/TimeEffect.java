/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Effect to apply when a libchaos-time rule matches.
 *
 * <p>Sealed algebraic data type covering the <strong>three</strong> effect kinds in the
 * libchaos-time rule grammar:
 *
 * <ul>
 *   <li>{@link ErrnoFault} — fail the syscall with a {@link TimeErrno} at a given probability
 *       (default {@code 1.0}). Valid on every selector.
 *   <li>{@link Latency} — delay the syscall by a {@link Duration} before delegating to libc, gated
 *       by probability. Valid on every selector.
 *   <li>{@link Offset} — signed-millisecond delta added to the {@code struct timespec} returned by
 *       {@code clock_gettime}. <strong>Valid only on {@link TimeSelector#CLOCK_GETTIME}</strong>
 *       (with or without a per-clock {@link TimeClock} qualifier).
 * </ul>
 *
 * <p>Selector × effect compatibility is enforced at {@link TimeRule} construction time via {@link
 * TimeSelector#acceptsOffset()}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/TIME.md">libchaos-time
 *     rule grammar</a>
 */
public sealed interface TimeEffect
    permits TimeEffect.ErrnoFault, TimeEffect.Latency, TimeEffect.Offset {

  /**
   * Renders this effect as the libchaos-time rule body fragment — the {@code
   * <effect-kind>:<value>[@<probability>]} suffix.
   *
   * @return non-null wire form, e.g. {@code "ERRNO:EINVAL@0.1"} or {@code "OFFSET:-1500"}
   */
  String wireForm();

  // ==================== Static factories ====================

  /**
   * @param errno errno to inject
   * @param probability probability in {@code (0.0, 1.0]}
   * @return errno-fault effect
   */
  static TimeEffect errno(final TimeErrno errno, final double probability) {
    return new ErrnoFault(errno, probability);
  }

  /** Deterministic errno injection ({@code probability == 1.0}). */
  static TimeEffect errno(final TimeErrno errno) {
    return new ErrnoFault(errno, 1.0);
  }

  /**
   * @param delay non-negative pre-call delay
   * @return latency effect with probability {@code 1.0}
   */
  static TimeEffect latency(final Duration delay) {
    return new Latency(delay, 1.0);
  }

  /**
   * @param delay non-negative pre-call delay
   * @param probability probability in {@code (0.0, 1.0]} that the delay fires
   * @return latency effect
   */
  static TimeEffect latency(final Duration delay, final double probability) {
    return new Latency(delay, probability);
  }

  /**
   * Signed-ms offset added to the returned {@code struct timespec} of {@code clock_gettime}. Only
   * valid when attached to {@link TimeSelector#CLOCK_GETTIME} rules.
   *
   * @param delta signed delta — negative values shift the clock into the past, positive into the
   *     future
   * @return offset effect with probability {@code 1.0}
   */
  static TimeEffect offset(final Duration delta) {
    return new Offset(delta, 1.0);
  }

  /**
   * @param delta signed delta added to {@code struct timespec}
   * @param probability probability in {@code (0.0, 1.0]} that the offset fires
   * @return offset effect
   */
  static TimeEffect offset(final Duration delta, final double probability) {
    return new Offset(delta, probability);
  }

  // ==================== Variants ====================

  /**
   * Inject a specific errno on the matched time syscall.
   *
   * <p><strong>Wire form:</strong> {@code ERRNO:<errno-name>[@<probability>]}. When {@code
   * probability == 1.0} the {@code @<probability>} suffix is omitted (libchaos-time defaults a
   * missing suffix to {@code 1.0}).
   */
  record ErrnoFault(TimeErrno errno, double probability) implements TimeEffect {
    public ErrnoFault {
      Objects.requireNonNull(errno, "errno must not be null");
      if (Double.isNaN(probability) || probability <= 0.0 || probability > 1.0) {
        throw new IllegalArgumentException(
            "probability must be in (0.0, 1.0], got: " + probability);
      }
    }

    @Override
    public String wireForm() {
      final String body = "ERRNO:" + errno.wireForm();
      return probability == 1.0 ? body : body + "@" + probability;
    }
  }

  /**
   * Delay the matched syscall by {@code delay}, gated by {@code probability}. Rendered as {@code
   * LATENCY:<ms>[@<probability>]} — the {@code @<probability>} suffix is omitted when {@code
   * probability == 1.0}.
   */
  record Latency(Duration delay, double probability) implements TimeEffect {
    public Latency {
      Objects.requireNonNull(delay, "delay must not be null");
      if (delay.isNegative()) {
        throw new IllegalArgumentException("delay must not be negative: " + delay);
      }
      if (Double.isNaN(probability) || probability <= 0.0 || probability > 1.0) {
        throw new IllegalArgumentException(
            "probability must be in (0.0, 1.0], got: " + probability);
      }
    }

    @Override
    public String wireForm() {
      final String body = "LATENCY:" + delay.toMillis();
      return probability == 1.0 ? body : body + "@" + probability;
    }
  }

  /**
   * Add a signed-ms delta to the {@code struct timespec} returned by {@code clock_gettime}.
   *
   * <p><strong>Wire form:</strong> {@code OFFSET:<signedMs>[@<probability>]}. The delta is signed
   * — {@link Duration#toMillis()} preserves the sign so negative offsets render as {@code
   * OFFSET:-1500}. The {@code @<probability>} suffix is omitted when {@code probability == 1.0}.
   *
   * <p>Selector compatibility (OFFSET is only meaningful on {@code clock_gettime}) is enforced at
   * {@link TimeRule} construction.
   */
  record Offset(Duration delta, double probability) implements TimeEffect {
    public Offset {
      Objects.requireNonNull(delta, "delta must not be null");
      if (Double.isNaN(probability) || probability <= 0.0 || probability > 1.0) {
        throw new IllegalArgumentException(
            "probability must be in (0.0, 1.0], got: " + probability);
      }
    }

    @Override
    public String wireForm() {
      final String body = "OFFSET:" + delta.toMillis();
      return probability == 1.0 ? body : body + "@" + probability;
    }
  }
}
