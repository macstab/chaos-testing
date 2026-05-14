/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosPattern composition combinators")
class CompositionTest {

  @Test
  @DisplayName("then concatenates two patterns with offset timestamps")
  void thenConcatenates() {
    final ChaosPattern<Double> head = RampPattern.linear(0, 50);
    final ChaosPattern<Double> tail = RampPattern.linear(50, 100);

    final List<TimedValue<Double>> samples =
        head.then(tail, Duration.ofSeconds(1))
            .generate(Duration.ofSeconds(2), Duration.ofMillis(500))
            .toList();

    // head: 0, 500, 1000ms (3 samples 0→50); tail: 1000, 1500, 2000ms (3 samples 50→100)
    assertThat(samples).isNotEmpty();
    assertThat(samples.get(0).timestamp()).isEqualTo(Duration.ZERO);
    assertThat(samples.get(0).value()).isCloseTo(0.0, org.assertj.core.api.Assertions.within(1.0));
    assertThat(samples.get(samples.size() - 1).timestamp())
        .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    assertThat(samples.get(samples.size() - 1).value())
        .isCloseTo(100.0, org.assertj.core.api.Assertions.within(1.0));
  }

  @Test
  @DisplayName("then with totalDuration <= thisDuration emits only the head pattern")
  void thenShortTotal() {
    final ChaosPattern<Double> head = RampPattern.linear(0, 50);
    final ChaosPattern<Double> tail = RampPattern.linear(50, 100);

    final List<TimedValue<Double>> samples =
        head.then(tail, Duration.ofSeconds(2))
            .generate(Duration.ofSeconds(1), Duration.ofMillis(500))
            .toList();

    assertThat(samples).isNotEmpty();
    assertThat(samples.get(samples.size() - 1).value()).isLessThan(60.0);
  }

  @Test
  @DisplayName("repeat re-emits the pattern n times with shifted timestamps")
  void repeatRepeats() {
    final ChaosPattern<Double> ramp = RampPattern.linear(0, 100);

    final List<TimedValue<Double>> samples =
        ramp.repeat(2).generate(Duration.ofSeconds(2), Duration.ofMillis(500)).toList();

    // Each repetition takes 1s; first ends near 100, second starts near 0 at t=1s
    assertThat(samples).isNotEmpty();
    final TimedValue<Double> secondStart =
        samples.stream().filter(tv -> tv.timestamp().toMillis() == 1000L).findFirst().orElseThrow();
    assertThat(secondStart.value()).isCloseTo(0.0, org.assertj.core.api.Assertions.within(1.0));
  }

  @Test
  @DisplayName("repeat(1) returns the same pattern instance")
  void repeatOne() {
    final ChaosPattern<Double> ramp = RampPattern.linear(0, 100);
    assertThat(ramp.repeat(1)).isSameAs(ramp);
  }

  @Test
  @DisplayName("repeat(0) rejects")
  void repeatZero() {
    final ChaosPattern<Double> ramp = RampPattern.linear(0, 100);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ramp.repeat(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
