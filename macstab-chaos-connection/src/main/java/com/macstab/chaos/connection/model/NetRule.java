/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

import java.time.Duration;
import java.util.Objects;

/**
 * A libchaos-net rule: an effect to apply on a syscall family at a given endpoint, with a
 * probability ({@code toxicity}).
 *
 * <p>Constructed via the validating static factories ({@link #errno}, {@link #latency}, {@link
 * #corrupt}, {@link #timeout}), each of which channels the caller into a known-safe combination of
 * {@link NetOperation} and {@link Effect}. The bare canonical constructor stays accessible but
 * enforces the same op×effect matrix as a defence-in-depth check.
 *
 * <p><strong>Op×effect compatibility matrix</strong> (libchaos-net contract):
 *
 * <table>
 *   <caption>Allowed (✓) and disallowed (✗) effect-on-operation pairings</caption>
 *   <tr><th></th><th>{@code ERRNO}</th><th>{@code LATENCY}</th><th>{@code CORRUPT}</th><th>{@code TIMEOUT}</th></tr>
 *   <tr><td>{@code SOCKET}/{@code BIND}/{@code LISTEN}/{@code CONNECT}/{@code ACCEPT}/{@code SHUTDOWN}/{@code SEND}</td><td>✓</td><td>✓</td><td>✗</td><td>✗</td></tr>
 *   <tr><td>{@code RECV}</td><td>✓</td><td>✓</td><td>✓</td><td>✗</td></tr>
 *   <tr><td>{@code POLL}</td><td>✗</td><td>✓</td><td>✗</td><td>✓</td></tr>
 * </table>
 *
 * @param endpoint endpoint selector (never {@code null})
 * @param operation syscall operation to intercept (never {@code null})
 * @param effect effect to apply when the rule matches (never {@code null})
 * @param toxicity probability in {@code (0.0, 1.0]} that the effect fires for any matched call
 * @author Christian Schnapka - Macstab GmbH
 */
public record NetRule(Endpoint endpoint, NetOperation operation, Effect effect, double toxicity) {

  /**
   * Canonical constructor — validates all components and the op×effect compatibility matrix.
   *
   * @throws NullPointerException if any reference component is {@code null}
   * @throws IllegalArgumentException if {@code toxicity} is outside {@code (0.0, 1.0]}, or if the
   *     {@code (operation, effect)} pair is not allowed by the libchaos-net grammar
   */
  public NetRule {
    Objects.requireNonNull(endpoint, "endpoint must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(effect, "effect must not be null");
    requireValidToxicity(toxicity);
    requireCompatible(operation, effect);
  }

  // ==================== Static factories ====================

  /**
   * Errno-fault rule. Rejects {@link NetOperation#POLL} (which uses {@link Effect.Timeout}
   * semantics).
   *
   * @param endpoint endpoint selector
   * @param operation syscall operation; must not be {@link NetOperation#POLL}
   * @param errno errno to inject
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return new rule
   * @throws IllegalArgumentException on POLL operation, invalid toxicity, or any component
   *     violating record-level invariants
   * @throws NullPointerException if any reference argument is {@code null}
   */
  public static NetRule errno(
      final Endpoint endpoint,
      final NetOperation operation,
      final Errno errno,
      final double toxicity) {
    return new NetRule(endpoint, operation, Effect.errno(errno), toxicity);
  }

  /**
   * Latency rule. Accepts every operation.
   *
   * @param endpoint endpoint selector
   * @param operation syscall operation
   * @param delay non-negative latency to inject
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return new rule
   */
  public static NetRule latency(
      final Endpoint endpoint,
      final NetOperation operation,
      final Duration delay,
      final double toxicity) {
    return new NetRule(endpoint, operation, Effect.latency(delay), toxicity);
  }

  /**
   * Corrupt-recv rule. Operation is implicitly {@link NetOperation#RECV} — corruption is only
   * meaningful on inbound payload.
   *
   * @param endpoint endpoint selector
   * @param rate corruption probability in {@code (0.0, 1.0]}
   * @param toxicity probability in {@code (0.0, 1.0]} that the rule fires per recv call
   * @return new rule
   */
  public static NetRule corrupt(
      final Endpoint endpoint, final double rate, final double toxicity) {
    return new NetRule(endpoint, NetOperation.RECV, Effect.corrupt(rate), toxicity);
  }

  /**
   * Poll-timeout rule. Operation is implicitly {@link NetOperation#POLL} — only meaningful on the
   * readiness-wait family.
   *
   * @param endpoint endpoint selector
   * @param duration strictly positive timeout
   * @param toxicity probability in {@code (0.0, 1.0]}
   * @return new rule
   */
  public static NetRule timeout(
      final Endpoint endpoint, final Duration duration, final double toxicity) {
    return new NetRule(endpoint, NetOperation.POLL, Effect.timeout(duration), toxicity);
  }

  // ==================== Validation helpers ====================

  private static void requireValidToxicity(final double toxicity) {
    if (Double.isNaN(toxicity) || toxicity <= 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          "toxicity must be in (0.0, 1.0], got: " + toxicity);
    }
  }

  private static void requireCompatible(final NetOperation op, final Effect effect) {
    switch (effect) {
      case Effect.ErrnoFault ignored -> {
        if (op == NetOperation.POLL) {
          throw new IllegalArgumentException(
              "ERRNO is not valid on POLL (POLL uses TIMEOUT semantics)");
        }
      }
      case Effect.Latency ignored -> {
        // valid on every operation
      }
      case Effect.Corrupt ignored -> {
        if (op != NetOperation.RECV) {
          throw new IllegalArgumentException(
              "CORRUPT is only valid on RECV, got: " + op);
        }
      }
      case Effect.Timeout ignored -> {
        if (op != NetOperation.POLL) {
          throw new IllegalArgumentException(
              "TIMEOUT is only valid on POLL, got: " + op);
        }
      }
    }
  }
}
