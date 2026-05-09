/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Effect")
class EffectTest {

  @Nested
  @DisplayName("ErrnoFault")
  class ErrnoFaultTests {

    @Test
    @DisplayName("renders ERRNO:<token>")
    void renders() {
      assertThat(Effect.errno(Errno.ECONNREFUSED).wireForm()).isEqualTo("ERRNO:ECONNREFUSED");
    }

    @Test
    @DisplayName("each errno produces a distinct wire form")
    void distinctPerErrno() {
      assertThat(Effect.errno(Errno.ETIMEDOUT).wireForm()).isEqualTo("ERRNO:ETIMEDOUT");
      assertThat(Effect.errno(Errno.EPIPE).wireForm()).isEqualTo("ERRNO:EPIPE");
    }

    @Test
    @DisplayName("null errno is rejected")
    void nullErrno() {
      assertThatThrownBy(() -> Effect.errno(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Latency")
  class LatencyTests {

    @Test
    @DisplayName("renders LATENCY:<ms>")
    void renders() {
      assertThat(Effect.latency(Duration.ofMillis(200)).wireForm()).isEqualTo("LATENCY:200");
    }

    @Test
    @DisplayName("seconds are converted to milliseconds")
    void secondsToMillis() {
      assertThat(Effect.latency(Duration.ofSeconds(2)).wireForm()).isEqualTo("LATENCY:2000");
    }

    @Test
    @DisplayName("zero duration is allowed (no-op latency)")
    void zeroAllowed() {
      assertThat(Effect.latency(Duration.ZERO).wireForm()).isEqualTo("LATENCY:0");
    }

    @Test
    @DisplayName("negative duration is rejected")
    void negativeRejected() {
      assertThatThrownBy(() -> Effect.latency(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("null duration is rejected")
    void nullDuration() {
      assertThatThrownBy(() -> Effect.latency(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Corrupt")
  class CorruptTests {

    @Test
    @DisplayName("renders CORRUPT:<rate>")
    void renders() {
      assertThat(Effect.corrupt(0.5).wireForm()).isEqualTo("CORRUPT:0.5");
    }

    @Test
    @DisplayName("rate at upper bound 1.0 is accepted")
    void upperBound() {
      assertThat(Effect.corrupt(1.0).wireForm()).isEqualTo("CORRUPT:1.0");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1, 1.0001, Double.MAX_VALUE, -Double.MAX_VALUE})
    @DisplayName("rate outside (0.0, 1.0] is rejected")
    void outOfRange(final double rate) {
      assertThatThrownBy(() -> Effect.corrupt(rate))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rate");
    }

    @Test
    @DisplayName("NaN rate is rejected")
    void nanRejected() {
      assertThatThrownBy(() -> Effect.corrupt(Double.NaN))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Timeout")
  class TimeoutTests {

    @Test
    @DisplayName("renders TIMEOUT:<ms>")
    void renders() {
      assertThat(Effect.timeout(Duration.ofMillis(5000)).wireForm()).isEqualTo("TIMEOUT:5000");
    }

    @Test
    @DisplayName("zero duration is rejected (timeout must be positive)")
    void zeroRejected() {
      assertThatThrownBy(() -> Effect.timeout(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("negative duration is rejected")
    void negativeRejected() {
      assertThatThrownBy(() -> Effect.timeout(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("null duration is rejected")
    void nullDuration() {
      assertThatThrownBy(() -> Effect.timeout(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("sealed hierarchy")
  class SealedHierarchy {

    @Test
    @DisplayName("pattern matching covers all four variants exhaustively")
    void exhaustive() {
      assertThat(kind(Effect.errno(Errno.EPIPE))).isEqualTo("errno");
      assertThat(kind(Effect.latency(Duration.ofMillis(1)))).isEqualTo("latency");
      assertThat(kind(Effect.corrupt(0.1))).isEqualTo("corrupt");
      assertThat(kind(Effect.timeout(Duration.ofMillis(1)))).isEqualTo("timeout");
    }

    private String kind(final Effect e) {
      return switch (e) {
        case Effect.ErrnoFault err -> "errno";
        case Effect.Latency l -> "latency";
        case Effect.Corrupt c -> "corrupt";
        case Effect.Timeout t -> "timeout";
      };
    }
  }
}
