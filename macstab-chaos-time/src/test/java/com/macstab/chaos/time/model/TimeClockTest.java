/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TimeClock (unit)")
class TimeClockTest {

  @Test
  @DisplayName("wireForm renders lowercase enum names")
  void wireForms() {
    assertThat(TimeClock.REALTIME.wireForm()).isEqualTo("realtime");
    assertThat(TimeClock.MONOTONIC.wireForm()).isEqualTo("monotonic");
    assertThat(TimeClock.MONOTONIC_RAW.wireForm()).isEqualTo("monotonic_raw");
    assertThat(TimeClock.REALTIME_COARSE.wireForm()).isEqualTo("realtime_coarse");
    assertThat(TimeClock.MONOTONIC_COARSE.wireForm()).isEqualTo("monotonic_coarse");
    assertThat(TimeClock.BOOTTIME.wireForm()).isEqualTo("boottime");
    assertThat(TimeClock.TAI.wireForm()).isEqualTo("tai");
    assertThat(TimeClock.PROCESS_CPUTIME_ID.wireForm()).isEqualTo("process_cputime_id");
    assertThat(TimeClock.THREAD_CPUTIME_ID.wireForm()).isEqualTo("thread_cputime_id");
  }

  @Test
  @DisplayName("9 clock-id tokens accepted by the libchaos-time C parser")
  void paletteBound() {
    assertThat(TimeClock.values()).hasSize(9);
  }
}
