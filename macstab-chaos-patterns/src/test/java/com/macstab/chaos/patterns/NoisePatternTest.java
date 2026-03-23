/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class NoisePatternTest {

  @Test
  void shouldBeRepeatableWithSameSeed() {
    final long seed = 42;
    final List<Double> run1 = new ArrayList<>();
    final List<Double> run2 = new ArrayList<>();

    NoisePattern.gaussian(50.0, 10.0, seed)
        .applyTo(run1::add, Duration.ofSeconds(1), Duration.ofMillis(100))
        .awaitUninterruptibly();

    NoisePattern.gaussian(50.0, 10.0, seed)
        .applyTo(run2::add, Duration.ofSeconds(1), Duration.ofMillis(100))
        .awaitUninterruptibly();

    assertThat(run1).isEqualTo(run2); // IDENTICAL!
  }
}
