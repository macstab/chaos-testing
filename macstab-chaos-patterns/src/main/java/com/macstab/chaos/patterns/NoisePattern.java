/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NoisePattern implements ChaosPattern<Double> {

  private final double baseline;
  private final double amplitude;
  private final long seed;
  private final Distribution distribution;

  private NoisePattern(
      final double baseline,
      final double amplitude,
      final long seed,
      final Distribution distribution) {
    this.baseline = baseline;
    this.amplitude = amplitude;
    this.seed = seed;
    this.distribution = distribution;
  }

  /**
   * Create Gaussian noise pattern (bell curve around baseline).
   *
   * <p>~68% of values within ±1 amplitude, ~95% within ±2 amplitudes.
   *
   * @param baseline center value
   * @param amplitude standard deviation
   * @param seed random seed (for repeatability)
   * @return Gaussian noise pattern
   */
  public static NoisePattern gaussian(
      final double baseline, final double amplitude, final long seed) {
    return new NoisePattern(baseline, amplitude, seed, Distribution.GAUSSIAN);
  }

  /**
   * Create uniform noise pattern (equal probability within range).
   *
   * <p>Values uniformly distributed in [baseline - amplitude, baseline + amplitude].
   *
   * @param baseline center value
   * @param amplitude maximum deviation
   * @param seed random seed
   * @return uniform noise pattern
   */
  public static NoisePattern uniform(
      final double baseline, final double amplitude, final long seed) {
    return new NoisePattern(baseline, amplitude, seed, Distribution.UNIFORM);
  }

  @Override
  public Stream<TimedValue<Double>> generate(
      final Duration totalDuration, final Duration sampleInterval) {
    Objects.requireNonNull(totalDuration, "totalDuration must not be null");
    Objects.requireNonNull(sampleInterval, "sampleInterval must not be null");

    final Random random = new Random(seed); // REPEATABLE!
    final long totalMillis = totalDuration.toMillis();
    final long intervalMillis = sampleInterval.toMillis();
    final long sampleCount = totalMillis / intervalMillis;

    return LongStream.rangeClosed(0, sampleCount)
        .mapToObj(
            i -> {
              final double noise = distribution.generate(random, amplitude);
              final double value = Math.max(0, baseline + noise); // Never negative
              final Duration timestamp = Duration.ofMillis(i * intervalMillis);
              return new TimedValue<>(timestamp, value);
            });
  }

  /** Random distribution type. */
  enum Distribution {
    /** Gaussian (normal) distribution. */
    GAUSSIAN {
      @Override
      double generate(final Random random, final double amplitude) {
        return random.nextGaussian() * amplitude;
      }
    },

    /** Uniform distribution. */
    UNIFORM {
      @Override
      double generate(final Random random, final double amplitude) {
        return (random.nextDouble() * 2 - 1) * amplitude; // [-amplitude, +amplitude]
      }
    };

    abstract double generate(Random random, double amplitude);
  }
}
