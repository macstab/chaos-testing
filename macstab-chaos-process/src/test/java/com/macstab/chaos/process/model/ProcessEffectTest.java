/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessEffect (unit)")
class ProcessEffectTest {

  @Nested
  @DisplayName("ErrnoFault")
  class ErrnoFaultCases {
    @Test
    @DisplayName("wireForm includes @probability when < 1.0")
    void belowOne() {
      assertThat(ProcessEffect.errno(ProcessErrno.EAGAIN, 0.001).wireForm())
          .isEqualTo("ERRNO:EAGAIN@0.001");
    }

    @Test
    @DisplayName("wireForm omits @probability when probability == 1.0")
    void atOne() {
      assertThat(ProcessEffect.errno(ProcessErrno.ENOENT, 1.0).wireForm())
          .isEqualTo("ERRNO:ENOENT");
      assertThat(ProcessEffect.errno(ProcessErrno.EAGAIN).wireForm()).isEqualTo("ERRNO:EAGAIN");
    }

    @Test
    @DisplayName("rejects null errno + bad probability")
    void rejects() {
      assertThatThrownBy(() -> ProcessEffect.errno(null, 0.5))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> ProcessEffect.errno(ProcessErrno.EAGAIN, 0.0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ProcessEffect.errno(ProcessErrno.EAGAIN, 1.1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ProcessEffect.errno(ProcessErrno.EAGAIN, Double.NaN))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Latency")
  class LatencyCases {
    @Test
    @DisplayName("wireForm is LATENCY:millis")
    void wireForm() {
      assertThat(ProcessEffect.latency(Duration.ofMillis(150)).wireForm()).isEqualTo("LATENCY:150");
      assertThat(ProcessEffect.latency(Duration.ZERO).wireForm()).isEqualTo("LATENCY:0");
    }

    @Test
    @DisplayName("rejects null / negative")
    void rejects() {
      assertThatThrownBy(() -> ProcessEffect.latency(null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> ProcessEffect.latency(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("FailAfter — unique to libchaos-process")
  class FailAfterCases {
    @Test
    @DisplayName("wireForm is FAIL_AFTER:<errno>,<count>")
    void wireForm() {
      assertThat(ProcessEffect.failAfter(ProcessErrno.EAGAIN, 128L).wireForm())
          .isEqualTo("FAIL_AFTER:EAGAIN,128");
    }

    @Test
    @DisplayName("count = 0 means first call fails (boundary OK)")
    void countZero() {
      assertThat(ProcessEffect.failAfter(ProcessErrno.EAGAIN, 0L).wireForm())
          .isEqualTo("FAIL_AFTER:EAGAIN,0");
    }

    @Test
    @DisplayName("rejects null errno + negative count")
    void rejects() {
      assertThatThrownBy(() -> ProcessEffect.failAfter(null, 10L))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> ProcessEffect.failAfter(ProcessErrno.EAGAIN, -1L))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
