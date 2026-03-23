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
}
