/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import static com.macstab.chaos.patterns.Durations.minutes;
import static com.macstab.chaos.patterns.Durations.seconds;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Demonstrates stunning fluent API. */
class FluentAPITest {

  @Test
  void cpuRampWithFluentAPI() {
    // Given: Capture CPU throttle values
    final List<Integer> cpuValues = new ArrayList<>();

    // When: Ramp from 10% to 90% over 1 second (READS LIKE ENGLISH!)
    FluentPatternBuilder.forInteger(cpuValues::add)
        .rampFrom(10)
        .to(90)
        .over(seconds(1))
        .execute()
        .awaitUninterruptibly();

    // Then: Values ramp from 10 → 90
    assertThat(cpuValues).hasSizeGreaterThan(5);
    assertThat(cpuValues.get(0)).isCloseTo(10, within(2));
    assertThat(cpuValues.get(cpuValues.size() / 2)).isCloseTo(50, within(5));
    assertThat(cpuValues.get(cpuValues.size() - 1)).isCloseTo(90, within(2));
  }

  @Test
  void cpuRampWithExponentialCurve() {
    final List<Integer> cpuValues = new ArrayList<>();

    // When: Exponential ramp (slow start, fast end)
    FluentPatternBuilder.forInteger(cpuValues::add)
        .rampFrom(10)
        .to(90)
        .over(seconds(1))
        .withCurve(RampPattern.RampCurve.EXPONENTIAL)
        .execute()
        .awaitUninterruptibly();

    // Then: Exponential curve (slow at start)
    assertThat(cpuValues.get(0)).isLessThan(20);
    assertThat(cpuValues.get(cpuValues.size() - 1)).isGreaterThan(85);
  }

  @Test
  void memoryNoiseWithFluentAPI() {
    final List<String> memoryValues = new ArrayList<>();

    // When: Random noise around 500MB ± 100MB (STUNNING API!)
    FluentPatternBuilder.forMemoryMB(memoryValues::add)
        .randomAround(500, 100)
        .withSeed(42)
        .forDuration(seconds(1))
        .execute()
        .awaitUninterruptibly();

    // Then: Values fluctuate around 500M
    assertThat(memoryValues).hasSizeGreaterThan(5);
    assertThat(memoryValues.get(0)).matches("\\d+M");
  }

  @Test
  void repeatableNoiseWithSeed() {
    final List<Integer> run1 = new ArrayList<>();
    final List<Integer> run2 = new ArrayList<>();

    // When: Execute same pattern twice with same seed
    FluentPatternBuilder.forInteger(run1::add)
        .randomAround(50, 15)
        .withSeed(42)
        .forDuration(seconds(1))
        .execute()
        .awaitUninterruptibly();

    FluentPatternBuilder.forInteger(run2::add)
        .randomAround(50, 15)
        .withSeed(42)
        .forDuration(seconds(1))
        .execute()
        .awaitUninterruptibly();

    // Then: IDENTICAL sequences (repeatable chaos!)
    assertThat(run1).isEqualTo(run2);
  }

  @Test
  void demonstrateAPIReadability() {
    // THIS IS STUNNING! Reads like English:

    // CPU: Ramp from 10% to 90% over 60 seconds with exponential curve
    FluentPatternBuilder.forInteger(this::setCpuThrottle)
        .rampFrom(10)
        .to(90)
        .over(seconds(60))
        .withCurve(RampPattern.RampCurve.EXPONENTIAL)
        .execute();

    // Memory: Random noise around 500MB ± 150MB for 5 minutes (repeatable)
    FluentPatternBuilder.forMemoryMB(this::setMemoryPressure)
        .randomAround(500, 150)
        .withSeed(42)
        .forDuration(minutes(5))
        .execute();

    // Disk: Ramp fill from 20% to 80% over 2 minutes
    FluentPatternBuilder.forInteger(percent -> fillDisk("/data", percent))
        .rampFrom(20)
        .to(80)
        .over(minutes(2))
        .execute();

    // IDE autocomplete guides you through the API!
    // Impossible to misuse!
  }

  // Mock operations
  private void setCpuThrottle(final int percent) {}

  private void setMemoryPressure(final String size) {}

  private void fillDisk(final String path, final int percent) {}

  private static org.assertj.core.data.Offset<Integer> within(final int value) {
    return org.assertj.core.data.Offset.offset(value);
  }
}
