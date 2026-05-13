/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Effect to apply when a libchaos-memory rule matches.
 *
 * <p>Sealed algebraic data type covering the two effect kinds in the libchaos-memory rule grammar:
 *
 * <ul>
 *   <li>{@link ErrnoFault} — fail the VM syscall with a specific {@link MmapErrno} at a given
 *       probability (default {@code 1.0}; lower values are crucial for sustained chaos without
 *       breaking unrelated infrastructure like SSH or the package installer)
 *   <li>{@link Latency} — delay the syscall by a {@link Duration} before delegating to libc; always
 *       applied when the rule matches (no probability dimension in the libchaos-memory grammar)
 * </ul>
 *
 * <p><strong>Selector compatibility</strong> is enforced at {@link MemoryRule} construction, not
 * here — see {@link MemorySelector#accepts(MmapErrno)}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/MEMORY.md">libchaos-memory
 *     rule grammar</a>
 */
public sealed interface MemoryEffect permits MemoryEffect.ErrnoFault, MemoryEffect.Latency {

  /**
   * Renders this effect as the libchaos-memory rule body fragment — the {@code
   * <effect-kind>:<value>[@<probability>]} suffix.
   *
   * @return non-null wire form, e.g. {@code "ERRNO:ENOMEM@0.001"} or {@code "LATENCY:50"}
   */
  String wireForm();

  // ==================== Static factories ====================

  /**
   * @param errno errno to inject
   * @param probability probability in {@code (0.0, 1.0]} that the failure fires when the rule
   *     matches
   * @return errno-fault effect
   */
  static MemoryEffect errno(final MmapErrno errno, final double probability) {
    return new ErrnoFault(errno, probability);
  }

  /**
   * Errno fault with deterministic firing (probability {@code 1.0}).
   *
   * @param errno errno to inject
   * @return errno-fault effect with probability {@code 1.0}
   */
  static MemoryEffect errno(final MmapErrno errno) {
    return new ErrnoFault(errno, 1.0);
  }

  /**
   * @param delay non-negative pre-call delay
   * @return latency effect with probability {@code 1.0}
   */
  static MemoryEffect latency(final Duration delay) {
    return new Latency(delay, 1.0);
  }

  /**
   * @param delay non-negative pre-call delay
   * @param probability probability in {@code (0.0, 1.0]} that the delay fires
   * @return latency effect
   */
  static MemoryEffect latency(final Duration delay, final double probability) {
    return new Latency(delay, probability);
  }

  // ==================== Variants ====================

  /**
   * Inject a specific errno on the matched VM syscall.
   *
   * <p><strong>Wire form:</strong> {@code ERRNO:<errno-name>@<probability>}. When {@code
   * probability == 1.0} the {@code @<probability>} suffix is omitted for cleanliness (libchaos
   * treats missing suffix as {@code 1.0}).
   */
  record ErrnoFault(MmapErrno errno, double probability) implements MemoryEffect {
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
      // Omit @1.0 — libchaos-memory treats missing suffix as 1.0 per docs §3.1
      return probability == 1.0 ? body : body + "@" + probability;
    }
  }

  /**
   * Delay the matched syscall by {@code delay}, gated by {@code probability}. Rendered as {@code
   * LATENCY:<ms>[@<probability>]} — the {@code @<probability>} suffix is omitted when {@code
   * probability == 1.0}.
   */
  record Latency(Duration delay, double probability) implements MemoryEffect {
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
}
