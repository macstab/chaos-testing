/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class BurstPattern implements ChaosPattern<Double> {

  private final double baselineValue;
  private final double spikeValue;
  private final Duration spikeDuration;
  private final Duration recoveryDuration;

  private BurstPattern(
      final double baselineValue,
      final double spikeValue,
      final Duration spikeDuration,
      final Duration recoveryDuration) {
    this.baselineValue = baselineValue;
    this.spikeValue = spikeValue;
    this.spikeDuration = spikeDuration;
    this.recoveryDuration = recoveryDuration;
  }

  /**
   * Create burst pattern.
   *
   * @param baselineValue baseline value (between bursts)
   * @param spikeValue spike value (during burst)
   * @param spikeDuration how long to hold spike
   * @param recoveryDuration how long to recover to baseline
   * @return burst pattern
   */
  public static BurstPattern create(
      final double baselineValue,
      final double spikeValue,
      final Duration spikeDuration,
      final Duration recoveryDuration) {
    return new BurstPattern(baselineValue, spikeValue, spikeDuration, recoveryDuration);
  }

  @Override
  public Stream<TimedValue<Double>> generate(
      final Duration totalDuration, final Duration sampleInterval) {
    Objects.requireNonNull(totalDuration, "totalDuration must not be null");
    Objects.requireNonNull(sampleInterval, "sampleInterval must not be null");

    final List<TimedValue<Double>> values = new ArrayList<>();
    final long totalMillis = totalDuration.toMillis();
    final long intervalMillis = sampleInterval.toMillis();
    final long spikeMillis = spikeDuration.toMillis();
    final long recoveryMillis = recoveryDuration.toMillis();
    final long cycleMillis = spikeMillis + recoveryMillis;

    long currentMillis = 0;
    while (currentMillis < totalMillis) {
      final long cycleProgress = currentMillis % cycleMillis;

      final double value;
      if (cycleProgress < spikeMillis) {
        // Spike phase: hold spike value
        value = spikeValue;
      } else {
        // Recovery phase: linear ramp from spike to baseline
        final long recoveryProgress = cycleProgress - spikeMillis;
        final double recoveryPercent = (double) recoveryProgress / recoveryMillis;
        value = spikeValue + (baselineValue - spikeValue) * recoveryPercent;
      }

      values.add(new TimedValue<>(Duration.ofMillis(currentMillis), value));
      currentMillis += intervalMillis;
    }

    return values.stream();
  }
}
