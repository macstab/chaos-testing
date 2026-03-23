/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class RampPattern implements ChaosPattern<Double> {

  private final double startValue;
  private final double endValue;
  private final RampCurve curve;

  private RampPattern(final double startValue, final double endValue, final RampCurve curve) {
    this.startValue = startValue;
    this.endValue = endValue;
    this.curve = curve;
  }

  /**
   * Create linear ramp (constant rate of change).
   *
   * @param startValue starting value
   * @param endValue ending value
   * @return linear ramp pattern
   */
  public static RampPattern linear(final double startValue, final double endValue) {
    return new RampPattern(startValue, endValue, RampCurve.LINEAR);
  }

  /**
   * Create exponential ramp (slow start, fast end).
   *
   * @param startValue starting value
   * @param endValue ending value
   * @return exponential ramp pattern
   */
  public static RampPattern exponential(final double startValue, final double endValue) {
    return new RampPattern(startValue, endValue, RampCurve.EXPONENTIAL);
  }

  /**
   * Create logarithmic ramp (fast start, slow end).
   *
   * @param startValue starting value
   * @param endValue ending value
   * @return logarithmic ramp pattern
   */
  public static RampPattern logarithmic(final double startValue, final double endValue) {
    return new RampPattern(startValue, endValue, RampCurve.LOGARITHMIC);
  }

  @Override
  public Stream<TimedValue<Double>> generate(
      final Duration totalDuration, final Duration sampleInterval) {
    Objects.requireNonNull(totalDuration, "totalDuration must not be null");
    Objects.requireNonNull(sampleInterval, "sampleInterval must not be null");

    final long totalMillis = totalDuration.toMillis();
    final long intervalMillis = sampleInterval.toMillis();
    final long sampleCount = totalMillis / intervalMillis;

    return LongStream.rangeClosed(0, sampleCount)
        .mapToObj(
            i -> {
              final double progress = (double) i / sampleCount; // 0.0 → 1.0
              final double value = curve.interpolate(startValue, endValue, progress);
              final Duration timestamp = Duration.ofMillis(i * intervalMillis);
              return new TimedValue<>(timestamp, value);
            });
  }

  /** Ramp curve type. */
  public enum RampCurve {
    /** Linear interpolation (constant rate). */
    LINEAR {
      @Override
      double interpolate(final double start, final double end, final double progress) {
        return start + (end - start) * progress;
      }
    },

    /** Exponential interpolation (slow start, fast end). */
    EXPONENTIAL {
      @Override
      double interpolate(final double start, final double end, final double progress) {
        final double expProgress = Math.pow(progress, 2); // x^2 curve
        return start + (end - start) * expProgress;
      }
    },

    /** Logarithmic interpolation (fast start, slow end). */
    LOGARITHMIC {
      @Override
      double interpolate(final double start, final double end, final double progress) {
        final double logProgress = Math.sqrt(progress); // sqrt(x) curve
        return start + (end - start) * logProgress;
      }
    };

    abstract double interpolate(double start, double end, double progress);
  }
}
