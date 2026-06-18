/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Effect to apply when a libchaos-process rule matches.
 *
 * <p>Sealed algebraic data type covering the <strong>three</strong> effect kinds in the
 * libchaos-process rule grammar — one more than every other libchaos library:
 *
 * <ul>
 *   <li>{@link ErrnoFault} — fail the syscall with a {@link ProcessErrno} at a given probability
 *       (default {@code 1.0})
 *   <li>{@link Latency} — delay the syscall by a {@link Duration} before delegating to libc; always
 *       applied when the rule matches (no probability dimension)
 *   <li>{@link FailAfter} — let the first {@code count} calls succeed, then fail every subsequent
 *       call with the configured errno. Models resource exhaustion (RLIMIT_NPROC, thread-pool
 *       limit). <strong>Unique to libchaos-process.</strong>
 * </ul>
 *
 * <p>Rule ordering inside libchaos-process: {@code LATENCY} fires first (unconditional pre-call
 * delay), {@code ERRNO} second (probabilistic failure gate), {@code FAIL_AFTER} third
 * (counter-gated failure). The real call fires only if no effect short-circuits it.
 *
 * <p>Selector compatibility is enforced at {@link ProcessRule} construction time via {@link
 * ProcessSelector#accepts(ProcessErrno)}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/PROCESS.md">libchaos-process
 *     rule grammar</a>
 */
public sealed interface ProcessEffect
    permits ProcessEffect.ErrnoFault, ProcessEffect.Latency, ProcessEffect.FailAfter {

  /**
   * Renders this effect as the libchaos-process rule body fragment — the {@code
   * <effect-kind>:<value>[@<probability>]} suffix.
   *
   * @return non-null wire form, e.g. {@code "ERRNO:EAGAIN@0.01"} or {@code "FAIL_AFTER:EAGAIN,128"}
   */
  String wireForm();

  // ==================== Static factories ====================

  /**
   * @param errno errno to inject
   * @param probability probability in {@code (0.0, 1.0]}
   * @return errno-fault effect
   */
  static ProcessEffect errno(final ProcessErrno errno, final double probability) {
    return new ErrnoFault(errno, probability);
  }

  /** Deterministic errno injection ({@code probability == 1.0}). */
  static ProcessEffect errno(final ProcessErrno errno) {
    return new ErrnoFault(errno, 1.0);
  }

  /**
   * @param delay non-negative pre-call delay
   * @return latency effect with probability {@code 1.0}
   */
  static ProcessEffect latency(final Duration delay) {
    return new Latency(delay, 1.0);
  }

  /**
   * @param delay non-negative pre-call delay
   * @param probability probability in {@code (0.0, 1.0]} that the delay fires
   * @return latency effect
   */
  static ProcessEffect latency(final Duration delay, final double probability) {
    return new Latency(delay, probability);
  }

  /**
   * @param errno errno to inject once the counter passes {@code count}
   * @param count number of calls that succeed before failure begins ({@code >= 0}; {@code 0} means
   *     the first call fails)
   * @return fail-after effect
   */
  static ProcessEffect failAfter(final ProcessErrno errno, final long count) {
    return new FailAfter(errno, count);
  }

  // ==================== Variants ====================

  /**
   * Inject a specific errno on the matched syscall.
   *
   * <p><strong>Wire form:</strong> {@code ERRNO:<errno-name>[@<probability>]}. When {@code
   * probability == 1.0} the {@code @<probability>} suffix is omitted (libchaos defaults missing
   * suffix to {@code 1.0}).
   */
  record ErrnoFault(ProcessErrno errno, double probability) implements ProcessEffect {
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
  record Latency(Duration delay, double probability) implements ProcessEffect {
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
   * Let the first {@code count} matched calls succeed; fail every subsequent call with {@code
   * errno}. Models resource exhaustion such as RLIMIT_NPROC or thread-pool limits.
   *
   * <p><strong>Wire form:</strong> {@code FAIL_AFTER:<errno-name>,<count>}.
   *
   * <p><strong>Counter reset:</strong> libchaos-process resets the per-rule counter to zero on
   * config reload. Writing a new config (or even re-writing the same one with a changed mtime)
   * restores the budget. For a single-shot exhaustion test, write the config, drive the test until
   * the {@code (count + 1)}-th call fails, then remove the config to restore normal operation.
   */
  record FailAfter(ProcessErrno errno, long count) implements ProcessEffect {
    public FailAfter {
      Objects.requireNonNull(errno, "errno must not be null");
      if (count < 0) {
        throw new IllegalArgumentException("count must not be negative: " + count);
      }
    }

    @Override
    public String wireForm() {
      return "FAIL_AFTER:" + errno.wireForm() + "," + count;
    }
  }
}
