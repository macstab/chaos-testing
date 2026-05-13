/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

import java.util.Locale;

/**
 * Per-clock-id sub-selector for libchaos-time's {@code clock_gettime/<clock>} selector form.
 *
 * <p>Only the nine clock-id names listed below are accepted by the libchaos-time C parser (see
 * {@code chaos_time_config.c} §parse_clock_id). Each token corresponds to a Linux {@code
 * clockid_t} constant; libchaos-time falls back gracefully when the running kernel lacks support
 * for a given clock.
 *
 * <p>A {@link TimeClock} is meaningful <strong>only</strong> when paired with {@link
 * TimeSelector#CLOCK_GETTIME}. {@link TimeRule} construction rejects any attempt to attach a
 * {@link TimeClock} to {@code nanosleep}, {@code usleep}, or the wildcard selector.
 *
 * <p>The {@link #wireForm()} is the canonical lowercase token written to the config file (e.g.
 * {@code monotonic_raw}, not {@code MONOTONIC_RAW}).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/TIME.md">libchaos-time
 *     rule grammar</a>
 */
public enum TimeClock {

  /** {@code CLOCK_REALTIME} — wall-clock time (settable). */
  REALTIME,

  /** {@code CLOCK_MONOTONIC} — monotonic-since-boot time (not settable). */
  MONOTONIC,

  /**
   * {@code CLOCK_MONOTONIC_RAW} — raw monotonic time unaffected by NTP discipline (Linux-specific).
   */
  MONOTONIC_RAW,

  /** {@code CLOCK_REALTIME_COARSE} — coarse-resolution wall-clock time. */
  REALTIME_COARSE,

  /** {@code CLOCK_MONOTONIC_COARSE} — coarse-resolution monotonic time. */
  MONOTONIC_COARSE,

  /** {@code CLOCK_BOOTTIME} — monotonic time including suspend (Linux-specific). */
  BOOTTIME,

  /** {@code CLOCK_TAI} — International Atomic Time (Linux-specific). */
  TAI,

  /** {@code CLOCK_PROCESS_CPUTIME_ID} — per-process CPU-time clock. */
  PROCESS_CPUTIME_ID,

  /** {@code CLOCK_THREAD_CPUTIME_ID} — per-thread CPU-time clock. */
  THREAD_CPUTIME_ID;

  /**
   * @return the libchaos-time clock-id token (lowercase enum name, e.g. {@code "realtime"}, {@code
   *     "monotonic_raw"})
   */
  public String wireForm() {
    return name().toLowerCase(Locale.ROOT);
  }
}
