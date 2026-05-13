/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Effect to apply when a libchaos-net rule matches.
 *
 * <p>Sealed algebraic data type covering the four effect kinds in the libchaos-net rule grammar:
 *
 * <ul>
 *   <li>{@link ErrnoFault} — fail the syscall with a specific {@link Errno}
 *   <li>{@link Latency} — delay the syscall by a {@link Duration}
 *   <li>{@link Corrupt} — corrupt {@code recv()} payload at a given rate
 *   <li>{@link Timeout} — return timeout from {@code poll()}-family syscalls
 * </ul>
 *
 * <p><strong>Op-compatibility</strong> is not enforced here — see {@link NetRule}'s factories,
 * which combine effect and {@link NetOperation} and reject invalid pairings.
 *
 * <p>Instantiate via the static factories ({@link #errno(Errno)}, {@link #latency(Duration)}, …)
 * for ergonomics, or directly via the nested records.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/NETWORK.md">libchaos-net
 *     rule grammar</a>
 */
public sealed interface Effect
    permits Effect.ErrnoFault, Effect.Latency, Effect.Corrupt, Effect.Timeout {

  /**
   * Renders this effect as the libchaos-net rule body fragment, without leading/trailing
   * separators.
   *
   * @return non-null wire form, e.g. {@code "ERRNO:ECONNREFUSED"} or {@code "LATENCY:200"}
   */
  String wireForm();

  // ==================== Static factories (ergonomic) ====================

  /**
   * @param errno errno to inject
   * @return errno-fault effect
   */
  static Effect errno(final Errno errno) {
    return new ErrnoFault(errno);
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
   * @param rate corruption probability in {@code (0.0, 1.0]}
   * @return corrupt effect
   */
  static Effect corrupt(final double rate) {
    return new Corrupt(rate);
  }

  /**
   * @param duration strictly positive timeout; rendered as milliseconds
   * @return timeout effect
   */
  static Effect timeout(final Duration duration) {
    return new Timeout(duration);
  }

  // ==================== Variants ====================

  /** Inject a specific errno on the matched syscall. */
  record ErrnoFault(Errno errno) implements Effect {
    public ErrnoFault {
      Objects.requireNonNull(errno, "errno must not be null");
    }

    @Override
    public String wireForm() {
      return "ERRNO:" + errno.wireForm();
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
   * Corrupt the {@code recv()} payload with the given probability. Only meaningful on {@link
   * NetOperation#RECV}; {@link NetRule}'s factory enforces this.
   */
  record Corrupt(double rate) implements Effect {
    public Corrupt {
      if (Double.isNaN(rate) || rate <= 0.0 || rate > 1.0) {
        throw new IllegalArgumentException("rate must be in (0.0, 1.0], got: " + rate);
      }
    }

    @Override
    public String wireForm() {
      return "CORRUPT:" + rate;
    }
  }

  /**
   * Force a timeout on {@code poll()}-family syscalls. Only meaningful on {@link
   * NetOperation#POLL}; {@link NetRule}'s factory enforces this.
   */
  record Timeout(Duration duration) implements Effect {
    public Timeout {
      Objects.requireNonNull(duration, "duration must not be null");
      if (duration.isNegative() || duration.isZero()) {
        throw new IllegalArgumentException("duration must be strictly positive: " + duration);
      }
    }

    @Override
    public String wireForm() {
      return "TIMEOUT:" + duration.toMillis();
    }
  }
}
