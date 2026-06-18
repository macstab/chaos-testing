/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TimeEffect (unit)")
class TimeEffectTest {

  @Nested
  @DisplayName("ErrnoFault")
  class ErrnoFaultCases {
    @Test
    @DisplayName("wireForm includes @probability when < 1.0")
    void belowOne() {
      assertThat(TimeEffect.errno(TimeErrno.EINVAL, 0.001).wireForm())
          .isEqualTo("ERRNO:EINVAL@0.001");
    }

    @Test
    @DisplayName("wireForm omits @probability when probability == 1.0")
    void atOne() {
      assertThat(TimeEffect.errno(TimeErrno.EINTR, 1.0).wireForm()).isEqualTo("ERRNO:EINTR");
      assertThat(TimeEffect.errno(TimeErrno.EFAULT).wireForm()).isEqualTo("ERRNO:EFAULT");
    }

    @Test
    @DisplayName("rejects null errno + bad probability")
    void rejects() {
      assertThatThrownBy(() -> TimeEffect.errno(null, 0.5))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> TimeEffect.errno(TimeErrno.EINTR, 0.0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> TimeEffect.errno(TimeErrno.EINTR, 1.1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> TimeEffect.errno(TimeErrno.EINTR, Double.NaN))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Latency")
  class LatencyCases {
    @Test
    @DisplayName("wireForm is LATENCY:millis")
    void wireForm() {
      assertThat(TimeEffect.latency(Duration.ofMillis(150)).wireForm()).isEqualTo("LATENCY:150");
      assertThat(TimeEffect.latency(Duration.ZERO).wireForm()).isEqualTo("LATENCY:0");
    }

    @Test
    @DisplayName("wireForm includes @probability when < 1.0")
    void belowOne() {
      assertThat(TimeEffect.latency(Duration.ofMillis(200), 0.5).wireForm())
          .isEqualTo("LATENCY:200@0.5");
    }

    @Test
    @DisplayName("rejects null / negative / bad probability")
    void rejects() {
      assertThatThrownBy(() -> TimeEffect.latency(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> TimeEffect.latency(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> TimeEffect.latency(Duration.ofMillis(10), 0.0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Offset — unique to libchaos-time")
  class OffsetCases {
    @Test
    @DisplayName("wireForm renders signed millis (positive)")
    void positive() {
      assertThat(TimeEffect.offset(Duration.ofMillis(1500)).wireForm()).isEqualTo("OFFSET:1500");
    }

    @Test
    @DisplayName("wireForm renders signed millis (negative)")
    void negative() {
      assertThat(TimeEffect.offset(Duration.ofMillis(-1500)).wireForm()).isEqualTo("OFFSET:-1500");
    }

    @Test
    @DisplayName("wireForm includes @probability when < 1.0")
    void belowOne() {
      assertThat(TimeEffect.offset(Duration.ofMillis(-500), 0.1).wireForm())
          .isEqualTo("OFFSET:-500@0.1");
    }

    @Test
    @DisplayName("zero delta is permitted")
    void zero() {
      assertThat(TimeEffect.offset(Duration.ZERO).wireForm()).isEqualTo("OFFSET:0");
    }

    @Test
    @DisplayName("rejects null + bad probability")
    void rejects() {
      assertThatThrownBy(() -> TimeEffect.offset(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> TimeEffect.offset(Duration.ofMillis(10), 0.0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> TimeEffect.offset(Duration.ofMillis(10), 1.1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
