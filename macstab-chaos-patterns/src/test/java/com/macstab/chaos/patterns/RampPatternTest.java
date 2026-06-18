/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RampPatternTest {

  @Test
  void shouldRampLinearlyFrom10To90() {
    final ChaosPattern<Double> ramp = RampPattern.linear(10, 90);
    final List<Double> values = new ArrayList<>();

    ramp.applyTo(values::add, Duration.ofSeconds(1), Duration.ofMillis(100)).awaitUninterruptibly();

    assertThat(values).hasSize(11);
    assertThat(values.get(0)).isCloseTo(10.0, within(1.0));
    assertThat(values.get(5)).isCloseTo(50.0, within(1.0));
    assertThat(values.get(10)).isCloseTo(90.0, within(1.0));
  }

  @Test
  void shouldWorkWithAnyOperation() {
    final ChaosPattern<Double> pattern = RampPattern.linear(0, 100);

    // CPU (simulated)
    pattern
        .applyTo(
            v -> setCpuThrottle((int) v.doubleValue()),
            Duration.ofMillis(500),
            Duration.ofMillis(100))
        .awaitUninterruptibly();

    // Memory (simulated)
    pattern
        .applyTo(
            v -> setMemory((int) v.doubleValue()), Duration.ofMillis(500), Duration.ofMillis(100))
        .awaitUninterruptibly();

    // SAME PATTERN, DIFFERENT OPERATIONS!
  }

  private void setCpuThrottle(final int percent) {}

  private void setMemory(final int mb) {}

  private static org.assertj.core.data.Offset<Double> within(final double value) {
    return org.assertj.core.data.Offset.offset(value);
  }
}
