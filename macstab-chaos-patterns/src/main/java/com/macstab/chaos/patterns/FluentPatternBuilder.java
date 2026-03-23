/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import static com.macstab.chaos.patterns.Durations.millis;

import java.time.Duration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class FluentPatternBuilder<T> {

  private final ValueConsumer<T> operation;
  private final ValueConverter<T> converter;
  private Duration sampleInterval = millis(100);

  private FluentPatternBuilder(
      final ValueConsumer<T> operation, final ValueConverter<T> converter) {
    this.operation = operation;
    this.converter = converter;
  }

  /**
   * Create builder for Integer values (CPU percent, disk percent, etc.).
   *
   * @param operation operation to execute
   * @return builder
   */
  public static FluentPatternBuilder<Integer> forInteger(final ValueConsumer<Integer> operation) {
    return new FluentPatternBuilder<>(operation, ValueConverter.toInteger());
  }

  /**
   * Create builder for String values (memory sizes: "512M", "1G").
   *
   * @param operation operation to execute
   * @return builder
   */
  public static FluentPatternBuilder<String> forMemoryMB(final ValueConsumer<String> operation) {
    return new FluentPatternBuilder<>(operation, ValueConverter.toMemoryMB());
  }

  /**
   * Create builder for Double values (custom operations).
   *
   * @param operation operation to execute
   * @return builder
   */
  public static FluentPatternBuilder<Double> forDouble(final ValueConsumer<Double> operation) {
    return new FluentPatternBuilder<>(operation, ValueConverter.identity());
  }

  // ========== RAMP PATTERN ==========

  /**
   * Start ramp pattern.
   *
   * @param startValue starting value
   * @return ramp step
   */
  public RampToStep rampFrom(final double startValue) {
    return new RampToStep(startValue);
  }

  public final class RampToStep {
    private final double startValue;

    RampToStep(final double startValue) {
      this.startValue = startValue;
    }

    public RampDurationStep to(final double endValue) {
      return new RampDurationStep(startValue, endValue);
    }
  }

  public final class RampDurationStep {
    private final double startValue;
    private final double endValue;

    RampDurationStep(final double startValue, final double endValue) {
      this.startValue = startValue;
      this.endValue = endValue;
    }

    public RampExecutableStep over(final Duration duration) {
      return new RampExecutableStep(startValue, endValue, duration);
    }
  }

  public final class RampExecutableStep {
    private final double startValue;
    private final double endValue;
    private final Duration duration;
    private RampPattern.RampCurve curve = RampPattern.RampCurve.LINEAR;

    RampExecutableStep(final double startValue, final double endValue, final Duration duration) {
      this.startValue = startValue;
      this.endValue = endValue;
      this.duration = duration;
    }

    public RampExecutableStep withCurve(final RampPattern.RampCurve curve) {
      this.curve = curve;
      return this;
    }

    public PatternExecution execute() {
      final ChaosPattern<Double> pattern;
      if (curve == RampPattern.RampCurve.EXPONENTIAL) {
        pattern = RampPattern.exponential(startValue, endValue);
      } else if (curve == RampPattern.RampCurve.LOGARITHMIC) {
        pattern = RampPattern.logarithmic(startValue, endValue);
      } else {
        pattern = RampPattern.linear(startValue, endValue);
      }

      final ChaosPattern<T> adapted = adaptPattern(pattern);
      return adapted.applyTo(operation, duration, sampleInterval);
    }
  }

  // ========== NOISE PATTERN ==========

  /**
   * Start noise pattern.
   *
   * @param baseline baseline value
   * @param amplitude noise amplitude
   * @return noise step
   */
  public NoiseDurationStep randomAround(final double baseline, final double amplitude) {
    return new NoiseDurationStep(baseline, amplitude);
  }

  public final class NoiseDurationStep {
    private final double baseline;
    private final double amplitude;
    private long seed = System.currentTimeMillis();

    NoiseDurationStep(final double baseline, final double amplitude) {
      this.baseline = baseline;
      this.amplitude = amplitude;
    }

    public NoiseDurationStep withSeed(final long seed) {
      this.seed = seed;
      return this;
    }

    public NoiseExecutableStep forDuration(final Duration duration) {
      return new NoiseExecutableStep(baseline, amplitude, seed, duration);
    }
  }

  public final class NoiseExecutableStep {
    private final double baseline;
    private final double amplitude;
    private final long seed;
    private final Duration duration;

    NoiseExecutableStep(
        final double baseline, final double amplitude, final long seed, final Duration duration) {
      this.baseline = baseline;
      this.amplitude = amplitude;
      this.seed = seed;
      this.duration = duration;
    }

    public PatternExecution execute() {
      final ChaosPattern<Double> pattern = NoisePattern.gaussian(baseline, amplitude, seed);
      final ChaosPattern<T> adapted = adaptPattern(pattern);
      return adapted.applyTo(operation, duration, sampleInterval);
    }
  }

  // ========== HELPER ==========

  @SuppressWarnings("unchecked")
  private ChaosPattern<T> adaptPattern(final ChaosPattern<Double> pattern) {
    return (totalDuration, sampleInterval) ->
        pattern
            .generate(totalDuration, sampleInterval)
            .map(tv -> new TimedValue<>(tv.timestamp(), converter.convert(tv.value())));
  }
}
