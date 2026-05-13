/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;

@DisplayName("TimeRuleSerializer (unit)")
class TimeRuleSerializerTest {

  @Test
  void errnoOnClockGettime() {
    assertThat(
            TimeRuleSerializer.serialize(
                TimeRule.errno(TimeSelector.CLOCK_GETTIME, TimeErrno.EINVAL)))
        .isEqualTo("clock_gettime:ERRNO:EINVAL");
  }

  @Test
  void errnoWithProbability() {
    assertThat(
            TimeRuleSerializer.serialize(
                TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR, 0.1)))
        .isEqualTo("nanosleep:ERRNO:EINTR@0.1");
  }

  @Test
  void latencyOnUsleep() {
    assertThat(
            TimeRuleSerializer.serialize(
                TimeRule.latency(TimeSelector.USLEEP, Duration.ofMillis(50))))
        .isEqualTo("usleep:LATENCY:50");
  }

  @Test
  @DisplayName("per-clock errno renders selector/clock-id form")
  void perClockErrno() {
    assertThat(
            TimeRuleSerializer.serialize(
                TimeRule.errno(TimeClock.MONOTONIC, TimeErrno.EINVAL, 0.5)))
        .isEqualTo("clock_gettime/monotonic:ERRNO:EINVAL@0.5");
  }

  @Test
  @DisplayName("OFFSET on unqualified clock_gettime")
  void offsetUnqualified() {
    assertThat(TimeRuleSerializer.serialize(TimeRule.offset(Duration.ofMillis(500))))
        .isEqualTo("clock_gettime:OFFSET:500");
  }

  @Test
  @DisplayName("OFFSET on per-clock clock_gettime renders signed millis with clock suffix")
  void offsetPerClockNegative() {
    assertThat(
            TimeRuleSerializer.serialize(
                TimeRule.offset(TimeClock.MONOTONIC, Duration.ofMillis(-1500))))
        .isEqualTo("clock_gettime/monotonic:OFFSET:-1500");
  }

  @Test
  void wildcardErrno() {
    assertThat(
            TimeRuleSerializer.serialize(
                TimeRule.errno(TimeSelector.WILDCARD, TimeErrno.EAGAIN, 0.05)))
        .isEqualTo("*:ERRNO:EAGAIN@0.05");
  }

  @Test
  void nanosleepEintr() {
    assertThat(
            TimeRuleSerializer.serialize(
                TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR, 1.0)))
        .isEqualTo("nanosleep:ERRNO:EINTR");
  }

  @Test
  void perClockBoottimeOffset() {
    assertThat(
            TimeRuleSerializer.serialize(
                TimeRule.offset(TimeClock.BOOTTIME, Duration.ofMillis(60_000), 0.25)))
        .isEqualTo("clock_gettime/boottime:OFFSET:60000@0.25");
  }

  @Test
  void perClockThreadCpuTimeIdLatency() {
    assertThat(
            TimeRuleSerializer.serialize(
                TimeRule.latency(TimeClock.THREAD_CPUTIME_ID, Duration.ofMillis(25))))
        .isEqualTo("clock_gettime/thread_cputime_id:LATENCY:25");
  }

  @Test
  void nullRejected() {
    assertThatThrownBy(() -> TimeRuleSerializer.serialize(null))
        .isInstanceOf(NullPointerException.class);
  }
}
