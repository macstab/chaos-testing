/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Effect (unit)")
class EffectTest {

  @Nested
  @DisplayName("ErrnoFault")
  class ErrnoFaultCases {

    @Test
    @DisplayName("renders as ERRNO_NAME:probability")
    void rendersAsErrno() {
      assertThat(Effect.errno(Errno.EIO, 0.3).wireForm()).isEqualTo("EIO:0.3");
      assertThat(Effect.errno(Errno.EMFILE, 1.0).wireForm()).isEqualTo("EMFILE:1.0");
    }

    @Test
    @DisplayName("rejects null errno")
    void rejectsNullErrno() {
      assertThatThrownBy(() -> Effect.errno(null, 0.5)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects probability outside (0.0, 1.0]")
    void rejectsBadProbability() {
      assertThatThrownBy(() -> Effect.errno(Errno.EIO, 0.0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Effect.errno(Errno.EIO, -0.1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Effect.errno(Errno.EIO, 1.1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Effect.errno(Errno.EIO, Double.NaN))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Latency")
  class LatencyCases {

    @Test
    @DisplayName("renders as LATENCY:millis")
    void rendersAsLatency() {
      assertThat(Effect.latency(Duration.ofMillis(250)).wireForm()).isEqualTo("LATENCY:250");
      assertThat(Effect.latency(Duration.ZERO).wireForm()).isEqualTo("LATENCY:0");
    }

    @Test
    @DisplayName("rejects null")
    void rejectsNull() {
      assertThatThrownBy(() -> Effect.latency(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects negative delay")
    void rejectsNegative() {
      assertThatThrownBy(() -> Effect.latency(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Torn")
  class TornCases {

    @Test
    @DisplayName("renders as TORN:probability")
    void rendersAsTorn() {
      assertThat(Effect.torn(0.1).wireForm()).isEqualTo("TORN:0.1");
    }

    @Test
    @DisplayName("rejects probability outside (0.0, 1.0]")
    void rejectsBadProbability() {
      assertThatThrownBy(() -> Effect.torn(0.0)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Effect.torn(1.5)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Corrupt")
  class CorruptCases {

    @Test
    @DisplayName("renders as CORRUPT:probability")
    void rendersAsCorrupt() {
      assertThat(Effect.corrupt(0.5).wireForm()).isEqualTo("CORRUPT:0.5");
    }

    @Test
    @DisplayName("rejects probability outside (0.0, 1.0]")
    void rejectsBadProbability() {
      assertThatThrownBy(() -> Effect.corrupt(0.0)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Effect.corrupt(2.0)).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
