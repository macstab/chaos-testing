/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * Generic pattern for temporal value generation.
 *
 * <p>Chaos-agnostic: generates values over time for ANY operation (CPU, memory, disk, custom).
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Create pattern (reusable!)
 * ChaosPattern<Integer> ramp = RampPattern.linear(10, 90, Duration.ofSeconds(60));
 *
 * // Apply to ANY operation
 * ramp.applyTo(percent -> setCpuThrottle(percent));
 * ramp.applyTo(percent -> setMemoryPressure(percent + "M"));
 * ramp.applyTo(percent -> fillDisk(percent));
 * }</pre>
 *
 * @param <T> value type (Integer, Double, String, custom)
 * @author Christian Schnapka - Macstab GmbH
 */
@FunctionalInterface
public interface ChaosPattern<T> {

  /**
   * Generate sequence of values over time.
   *
   * <p>Each value includes timestamp (when to apply) + value (what to apply).
   *
   * @param totalDuration total pattern duration
   * @param sampleInterval interval between samples (e.g., 100ms)
   * @return stream of timestamped values
   */
  Stream<TimedValue<T>> generate(Duration totalDuration, Duration sampleInterval);

  /**
   * Apply pattern to operation (background execution).
   *
   * <p>Non-blocking: returns immediately, executes in background thread.
   *
   * @param operation operation to execute at each timestamp
   * @param totalDuration pattern duration
   * @param sampleInterval sampling interval
   * @return execution handle (for stop/await)
   */
  default PatternExecution applyTo(
      ValueConsumer<T> operation, Duration totalDuration, Duration sampleInterval) {
    return PatternExecutor.execute(this, operation, totalDuration, sampleInterval);
  }

  /**
   * Apply pattern with default 100ms sampling.
   *
   * @param operation operation to execute
   * @param totalDuration pattern duration
   * @return execution handle
   */
  default PatternExecution applyTo(ValueConsumer<T> operation, Duration totalDuration) {
    return applyTo(operation, totalDuration, Duration.ofMillis(100));
  }

  /**
   * Apply pattern with explicit failure policy and sampling.
   *
   * @param operation operation to execute
   * @param totalDuration pattern duration
   * @param sampleInterval sampling interval
   * @param failurePolicy policy controlling abort-on-error behaviour
   * @return execution handle
   */
  default PatternExecution applyTo(
      ValueConsumer<T> operation,
      Duration totalDuration,
      Duration sampleInterval,
      FailurePolicy failurePolicy) {
    return PatternExecutor.execute(this, operation, totalDuration, sampleInterval, failurePolicy);
  }

  // ==================== Composition combinators ====================

  /**
   * Concatenate this pattern (running for {@code thisDuration}) with {@code next} (filling the
   * remainder of the total duration passed to {@link #generate}). When the composite pattern is
   * evaluated:
   *
   * <ol>
   *   <li>{@code this} runs from {@code t=0} until {@code thisDuration}
   *   <li>{@code next} runs from {@code thisDuration} until {@code totalDuration}
   * </ol>
   *
   * <p>Timestamps from {@code next} are offset by {@code thisDuration} so the merged stream stays
   * monotonically increasing. If the total duration is shorter than {@code thisDuration} the second
   * pattern is skipped.
   *
   * @param next pattern to run after this one
   * @param thisDuration how long this pattern owns the timeline
   * @return composite pattern
   */
  default ChaosPattern<T> then(final ChaosPattern<T> next, final Duration thisDuration) {
    return (totalDuration, sampleInterval) -> {
      if (totalDuration.compareTo(thisDuration) <= 0) {
        return this.generate(totalDuration, sampleInterval);
      }
      final Duration nextDuration = totalDuration.minus(thisDuration);
      final Stream<TimedValue<T>> head = this.generate(thisDuration, sampleInterval);
      final Stream<TimedValue<T>> tail =
          next.generate(nextDuration, sampleInterval)
              .map(tv -> new TimedValue<>(thisDuration.plus(tv.timestamp()), tv.value()));
      return Stream.concat(head, tail);
    };
  }

  /**
   * Repeat this pattern {@code n} times across the total duration, dividing each repetition evenly.
   * For {@code n == 1} this is a no-op.
   *
   * @param n repetition count ({@code >= 1})
   * @return composite pattern
   * @throws IllegalArgumentException if {@code n < 1}
   */
  default ChaosPattern<T> repeat(final int n) {
    if (n < 1) {
      throw new IllegalArgumentException("n must be >= 1, got: " + n);
    }
    if (n == 1) {
      return this;
    }
    final ChaosPattern<T> self = this;
    return (totalDuration, sampleInterval) -> {
      final Duration chunk = totalDuration.dividedBy(n);
      return java.util.stream.IntStream.range(0, n)
          .boxed()
          .flatMap(
              i -> {
                java.util.stream.Stream<TimedValue<T>> s =
                    self.generate(chunk, sampleInterval)
                        .map(
                            tv ->
                                new TimedValue<>(
                                    chunk.multipliedBy(i).plus(tv.timestamp()), tv.value()));
                // Drop the boundary sample of non-last chunks to avoid duplicate timestamps
                // where the end of chunk i and the start of chunk i+1 overlap.
                if (i < n - 1) {
                  s = s.filter(tv -> tv.timestamp().compareTo(chunk.multipliedBy(i + 1)) < 0);
                }
                return s;
              });
    };
  }
}
