/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class WavePattern implements ChaosPattern<Double> {

  private final double minValue;
  private final double maxValue;
  private final Duration period;
  private final WaveType type;

  private WavePattern(
      final double minValue, final double maxValue, final Duration period, final WaveType type) {
    this.minValue = minValue;
    this.maxValue = maxValue;
    this.period = period;
    this.type = type;
  }

  /**
   * Create sine wave pattern (smooth oscillation).
   *
   * @param minValue minimum value
   * @param maxValue maximum value
   * @param period oscillation period
   * @return sine wave pattern
   */
  public static WavePattern sine(
      final double minValue, final double maxValue, final Duration period) {
    return new WavePattern(minValue, maxValue, period, WaveType.SINE);
  }

  /**
   * Create square wave pattern (abrupt switches).
   *
   * @param minValue minimum value
   * @param maxValue maximum value
   * @param period oscillation period
   * @return square wave pattern
   */
  public static WavePattern square(
      final double minValue, final double maxValue, final Duration period) {
    return new WavePattern(minValue, maxValue, period, WaveType.SQUARE);
  }

  /**
   * Create sawtooth wave pattern (linear ramp + reset).
   *
   * @param minValue minimum value
   * @param maxValue maximum value
   * @param period oscillation period
   * @return sawtooth wave pattern
   */
  public static WavePattern sawtooth(
      final double minValue, final double maxValue, final Duration period) {
    return new WavePattern(minValue, maxValue, period, WaveType.SAWTOOTH);
  }

  @Override
  public Stream<TimedValue<Double>> generate(
      final Duration totalDuration, final Duration sampleInterval) {
    Objects.requireNonNull(totalDuration, "totalDuration must not be null");
    Objects.requireNonNull(sampleInterval, "sampleInterval must not be null");

    final long totalMillis = totalDuration.toMillis();
    final long intervalMillis = sampleInterval.toMillis();
    final long periodMillis = period.toMillis();
    final long sampleCount = totalMillis / intervalMillis;

    return LongStream.rangeClosed(0, sampleCount)
        .mapToObj(
            i -> {
              final long timeMillis = i * intervalMillis;
              final double phaseProgress =
                  (double) (timeMillis % periodMillis) / periodMillis; // 0.0 → 1.0
              final double value = type.calculate(minValue, maxValue, phaseProgress);
              final Duration timestamp = Duration.ofMillis(timeMillis);
              return new TimedValue<>(timestamp, value);
            });
  }

  /** Wave type. */
  enum WaveType {
    /** Sine wave (smooth). */
    SINE {
      @Override
      double calculate(final double min, final double max, final double phase) {
        final double amplitude = (max - min) / 2;
        final double offset = (max + min) / 2;
        return offset + amplitude * Math.sin(2 * Math.PI * phase);
      }
    },

    /** Square wave (abrupt). */
    SQUARE {
      @Override
      double calculate(final double min, final double max, final double phase) {
        return phase < 0.5 ? max : min;
      }
    },

    /** Sawtooth wave (linear ramp). */
    SAWTOOTH {
      @Override
      double calculate(final double min, final double max, final double phase) {
        return min + (max - min) * phase;
      }
    };

    abstract double calculate(double min, double max, double phase);
  }
}
