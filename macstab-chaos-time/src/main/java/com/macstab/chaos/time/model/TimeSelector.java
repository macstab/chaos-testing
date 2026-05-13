/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

import java.util.Locale;

/**
 * Selector for a libchaos-time rule — identifies which time-syscall libc symbol the rule applies
 * to.
 *
 * <p>libchaos-time hooks three POSIX entry points at the libc boundary plus a wildcard matching
 * every interposed call:
 *
 * <ul>
 *   <li>{@link #CLOCK_GETTIME} — {@code clock_gettime()} across all clocks; may be additionally
 *       narrowed to a single {@link TimeClock} via {@link TimeRule}'s optional {@code clock}
 *       qualifier (renders as {@code clock_gettime/<clock-name>} on the wire).
 *   <li>{@link #NANOSLEEP} — {@code nanosleep()}
 *   <li>{@link #USLEEP} — {@code usleep()}
 *   <li>{@link #WILDCARD} — every interposed call
 * </ul>
 *
 * <p><strong>Effect compatibility</strong> (validated at {@link TimeRule} construction):
 *
 * <ul>
 *   <li>{@code ERRNO} and {@code LATENCY} are accepted by every selector.
 *   <li>{@code OFFSET} is accepted <strong>only</strong> on {@link #CLOCK_GETTIME} (with or
 *       without a {@link TimeClock} qualifier). OFFSET modifies the returned {@code struct
 *       timespec} and is meaningless on {@code nanosleep} / {@code usleep} / the wildcard
 *       selector. The libchaos-time C parser rejects such combinations and so does {@link
 *       TimeRule}.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see TimeClock
 * @see TimeRule
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/TIME.md">libchaos-time
 *     rule grammar</a>
 */
public enum TimeSelector {

  /** {@code clock_gettime()} — clock read across all clocks (narrow further via {@link TimeClock}). */
  CLOCK_GETTIME,

  /** {@code nanosleep()} — high-resolution sleep. */
  NANOSLEEP,

  /** {@code usleep()} — microsecond-resolution sleep. */
  USLEEP,

  /** Wildcard — matches every interposed call. Rejects {@code OFFSET}. */
  WILDCARD;

  /**
   * @return the libchaos-time selector token (e.g. {@code "clock_gettime"}, {@code "nanosleep"},
   *     {@code "*"}). The optional {@code /clock-id} suffix is rendered by the rule serializer when
   *     a {@link TimeClock} qualifier is present.
   */
  public String wireForm() {
    return this == WILDCARD ? "*" : name().toLowerCase(Locale.ROOT);
  }

  /**
   * Indicates whether this selector accepts an {@code OFFSET} effect.
   *
   * @return {@code true} iff this is {@link #CLOCK_GETTIME}; OFFSET is rejected for every other
   *     selector by both libchaos-time's C parser and {@link TimeRule}.
   */
  public boolean acceptsOffset() {
    return this == CLOCK_GETTIME;
  }

  /**
   * Indicates whether this selector accepts a {@link TimeClock} qualifier.
   *
   * @return {@code true} iff this is {@link #CLOCK_GETTIME}
   */
  public boolean acceptsClockQualifier() {
    return this == CLOCK_GETTIME;
  }
}
