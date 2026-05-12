/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.model;

import java.time.Duration;
import java.util.Objects;

/**
 * A libchaos-memory rule: an effect to apply on every interposed call that matches a {@link
 * MemorySelector}.
 *
 * <p>Constructed via the validating static factories ({@link #errno}, {@link #latency}), each of
 * which channels the caller into a known-safe combination of {@link MemorySelector} and {@link
 * MemoryEffect}. The bare canonical constructor stays accessible but enforces the same selector ×
 * errno compatibility matrix as a defence-in-depth check.
 *
 * <p><strong>Why the matrix matters.</strong> libchaos-memory itself does not validate errno
 * legality per selector; an invalid combination such as {@code munmap:ERRNO:EACCES} would load but
 * silently no-op at runtime, which is a surprising failure mode. Enforcing the matrix at Java
 * construction time gives users a clear error at the call site.
 *
 * @param selector selector that picks the matching VM syscalls (never {@code null})
 * @param effect effect to apply when the rule matches (never {@code null})
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/MEMORY.md">libchaos-memory
 *     rule grammar</a>
 */
public record MemoryRule(MemorySelector selector, MemoryEffect effect) {

  /**
   * Canonical constructor — validates components and the selector × errno compatibility matrix.
   *
   * @throws NullPointerException if any reference component is {@code null}
   * @throws IllegalArgumentException if the {@code (selector, effect.errno)} pair is not in {@link
   *     MemorySelector#validErrnos()} for the selector
   */
  public MemoryRule {
    Objects.requireNonNull(selector, "selector must not be null");
    Objects.requireNonNull(effect, "effect must not be null");
    requireCompatible(selector, effect);
  }

  // ==================== Static factories ====================

  /**
   * Errno-fault rule with the given probability.
   *
   * @param selector selector picking the matched calls
   * @param errno errno to inject
   * @param probability probability in {@code (0.0, 1.0]}
   * @return new rule
   * @throws IllegalArgumentException if {@code errno} is not in {@link
   *     MemorySelector#validErrnos()} for {@code selector}
   */
  public static MemoryRule errno(
      final MemorySelector selector, final MmapErrno errno, final double probability) {
    return new MemoryRule(selector, MemoryEffect.errno(errno, probability));
  }

  /**
   * Errno-fault rule with deterministic firing (probability {@code 1.0}).
   *
   * @param selector selector picking the matched calls
   * @param errno errno to inject
   * @return new rule
   */
  public static MemoryRule errno(final MemorySelector selector, final MmapErrno errno) {
    return new MemoryRule(selector, MemoryEffect.errno(errno));
  }

  /**
   * Latency rule. Valid on every selector. The delay is always applied when the rule matches —
   * libchaos-memory has no probability dimension for latency.
   *
   * @param selector selector picking the matched calls
   * @param delay non-negative latency
   * @return new rule
   */
  public static MemoryRule latency(final MemorySelector selector, final Duration delay) {
    return new MemoryRule(selector, MemoryEffect.latency(delay));
  }

  // ==================== Validation helpers ====================

  private static void requireCompatible(final MemorySelector selector, final MemoryEffect effect) {
    if (!(effect instanceof MemoryEffect.ErrnoFault fault)) {
      return; // LATENCY is valid on every selector
    }
    if (!selector.accepts(fault.errno())) {
      throw new IllegalArgumentException(
          fault.errno().wireForm()
              + " is not a valid errno for selector "
              + selector.wireForm()
              + ". Accepted errnos: "
              + selector.validErrnos());
    }
  }
}
