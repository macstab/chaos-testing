/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FailurePolicy + PatternExecutor abort/continue behaviour")
class FailurePolicyTest {

  @Test
  @DisplayName("ABORT halts on the first failure — fewer than total samples observed")
  void abortStopsImmediately() {
    final ChaosPattern<Double> ramp = RampPattern.linear(0, 100);
    final AtomicInteger calls = new AtomicInteger();

    ramp.applyTo(
            v -> {
              calls.incrementAndGet();
              throw new RuntimeException("boom");
            },
            Duration.ofMillis(500),
            Duration.ofMillis(100),
            FailurePolicy.ABORT)
        .awaitUninterruptibly();

    assertThat(calls.get()).isLessThanOrEqualTo(2);
  }

  @Test
  @DisplayName("IGNORE keeps going despite failures — full sample count observed")
  void ignoreContinues() {
    final ChaosPattern<Double> ramp = RampPattern.linear(0, 100);
    final AtomicInteger calls = new AtomicInteger();

    ramp.applyTo(
            v -> {
              calls.incrementAndGet();
              throw new RuntimeException("boom");
            },
            Duration.ofMillis(500),
            Duration.ofMillis(100),
            FailurePolicy.IGNORE)
        .awaitUninterruptibly();

    assertThat(calls.get()).isGreaterThanOrEqualTo(5);
  }

  @Test
  @DisplayName("stopAfter(2) tolerates 2 consecutive failures then aborts on the 3rd")
  void stopAfterCountsConsecutive() {
    final ChaosPattern<Double> ramp = RampPattern.linear(0, 100);
    final AtomicInteger calls = new AtomicInteger();

    ramp.applyTo(
            v -> {
              calls.incrementAndGet();
              throw new RuntimeException("boom");
            },
            Duration.ofMillis(500),
            Duration.ofMillis(100),
            FailurePolicy.stopAfter(2))
        .awaitUninterruptibly();

    assertThat(calls.get()).isLessThanOrEqualTo(4);
    assertThat(calls.get()).isGreaterThanOrEqualTo(3);
  }

  @Test
  @DisplayName("stopAfter(n) with n < 0 rejects")
  void stopAfterNegative() {
    assertThatThrownBy(() -> FailurePolicy.stopAfter(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
