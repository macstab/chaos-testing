/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Effect to apply when a libchaos-io rule matches.
 *
 * <p>Sealed algebraic data type covering the four effect kinds in the libchaos-io rule grammar:
 *
 * <ul>
 *   <li>{@link ErrnoFault} — fail the syscall with a specific {@link Errno}
 *   <li>{@link Latency} — delay the syscall by a {@link Duration} before delegating to libc
 *   <li>{@link Torn} — shorten a successful write's byte count
 *   <li>{@link Corrupt} — flip one bit in a successful read's returned buffer after libc returns
 * </ul>
 *
 * <p><strong>Op-compatibility</strong> is not enforced here — see {@link IoRule}'s factories, which
 * combine effect and {@link IoOperation} and reject invalid pairings.
 *
 * <p>Instantiate via the static factories ({@link #errno(Errno, double)}, {@link
 * #latency(Duration)}, …) for ergonomics, or directly via the nested records.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/IO.md">libchaos-io
 *     rule grammar</a>
 */
public sealed interface Effect
    permits Effect.ErrnoFault, Effect.Latency, Effect.Torn, Effect.Corrupt {

  /**
   * Renders this effect as the libchaos-io rule body fragment, without leading/trailing separators.
   *
   * <p>The libchaos-io rule grammar is {@code <path>:<op>:<effect-tag>:<value>}, so this method
   * returns the {@code <effect-tag>:<value>} pair as a single string.
   *
   * @return non-null wire form, e.g. {@code "EIO:0.3"}, {@code "LATENCY:200"}
   */
  String wireForm();

  // ==================== Static factories (ergonomic) ====================

  /**
   * @param errno errno to inject
   * @param probability probability in {@code (0.0, 1.0]} that the errno fires when the rule matches
   * @return errno-fault effect
   */
  static Effect errno(final Errno errno, final double probability) {
    return new ErrnoFault(errno, probability);
  }

  /**
   * @param delay non-negative delay; rendered as milliseconds
   * @return latency effect with probability {@code 1.0}
   */
  static Effect latency(final Duration delay) {
    return new Latency(delay, 1.0);
  }

  /**
   * @param delay non-negative delay; rendered as milliseconds
   * @param probability probability in {@code (0.0, 1.0]} that the delay fires
   * @return latency effect
   */
  static Effect latency(final Duration delay, final double probability) {
    return new Latency(delay, probability);
  }

  /**
   * @param probability probability in {@code (0.0, 1.0]} that a write is torn when the rule matches
   * @return torn-write effect
   */
  static Effect torn(final double probability) {
    return new Torn(probability);
  }

  /**
   * @param probability probability in {@code (0.0, 1.0]} that a read buffer is corrupted when the
   *     rule matches
   * @return corrupt-read effect
   */
  static Effect corrupt(final double probability) {
    return new Corrupt(probability);
  }

  // ==================== Variants ====================

  /**
   * Inject a specific errno on the matched syscall.
   *
   * <p>The libchaos-io grammar embeds the errno name as the effect tag — e.g. {@code EIO:0.3} —
   * rather than the literal token {@code ERRNO}.
   */
  record ErrnoFault(Errno errno, double probability) implements Effect {
    public ErrnoFault {
      Objects.requireNonNull(errno, "errno must not be null");
      requireValidProbability(probability, "probability");
    }

    @Override
    public String wireForm() {
      return errno.wireForm() + ":" + probability;
    }
  }

  /**
   * Delay the matched syscall by {@code delay}, gated by {@code probability}. Rendered as {@code
   * LATENCY:<ms>[@<probability>]} — the {@code @<probability>} suffix is omitted when {@code
   * probability == 1.0}.
   */
  record Latency(Duration delay, double probability) implements Effect {
    public Latency {
      Objects.requireNonNull(delay, "delay must not be null");
      if (delay.isNegative()) {
        throw new IllegalArgumentException("delay must not be negative: " + delay);
      }
      requireValidProbability(probability, "probability");
    }

    @Override
    public String wireForm() {
      final String body = "LATENCY:" + delay.toMillis();
      return probability == 1.0 ? body : body + "@" + probability;
    }
  }

  /**
   * Successful short write — the wrapper reduces the byte count before delegating to libc. Only
   * meaningful on {@link IoOperation#WRITE} and {@link IoOperation#PWRITE}; {@link IoRule}'s
   * canonical constructor enforces this.
   */
  record Torn(double probability) implements Effect {
    public Torn {
      requireValidProbability(probability, "probability");
    }

    @Override
    public String wireForm() {
      return "TORN:" + probability;
    }
  }

  /**
   * Single-bit corruption of a successful read's returned buffer. Only meaningful on {@link
   * IoOperation#READ} and {@link IoOperation#PREAD}; {@link IoRule}'s canonical constructor
   * enforces this.
   */
  record Corrupt(double probability) implements Effect {
    public Corrupt {
      requireValidProbability(probability, "probability");
    }

    @Override
    public String wireForm() {
      return "CORRUPT:" + probability;
    }
  }

  // ==================== Validation ====================

  private static void requireValidProbability(final double value, final String fieldName) {
    if (Double.isNaN(value) || value <= 0.0 || value > 1.0) {
      throw new IllegalArgumentException(fieldName + " must be in (0.0, 1.0], got: " + value);
    }
  }
}
