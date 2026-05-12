/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MemoryEffect (unit)")
class MemoryEffectTest {

  @Nested
  @DisplayName("ErrnoFault")
  class ErrnoFaultCases {
    @Test
    @DisplayName("wireForm includes @probability when < 1.0")
    void belowOne() {
      assertThat(MemoryEffect.errno(MmapErrno.ENOMEM, 0.001).wireForm())
          .isEqualTo("ERRNO:ENOMEM@0.001");
    }

    @Test
    @DisplayName("wireForm omits @probability when probability == 1.0")
    void atOne() {
      assertThat(MemoryEffect.errno(MmapErrno.ENOMEM, 1.0).wireForm()).isEqualTo("ERRNO:ENOMEM");
      assertThat(MemoryEffect.errno(MmapErrno.EACCES).wireForm()).isEqualTo("ERRNO:EACCES");
    }

    @Test
    @DisplayName("rejects null errno")
    void nullErrno() {
      assertThatThrownBy(() -> MemoryEffect.errno(null, 0.5))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects probability outside (0.0, 1.0]")
    void badProbability() {
      assertThatThrownBy(() -> MemoryEffect.errno(MmapErrno.ENOMEM, 0.0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> MemoryEffect.errno(MmapErrno.ENOMEM, -0.1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> MemoryEffect.errno(MmapErrno.ENOMEM, 1.1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> MemoryEffect.errno(MmapErrno.ENOMEM, Double.NaN))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Latency")
  class LatencyCases {
    @Test
    @DisplayName("wireForm is LATENCY:millis")
    void wireForm() {
      assertThat(MemoryEffect.latency(Duration.ofMillis(50)).wireForm()).isEqualTo("LATENCY:50");
      assertThat(MemoryEffect.latency(Duration.ZERO).wireForm()).isEqualTo("LATENCY:0");
    }

    @Test
    @DisplayName("rejects null / negative")
    void rejects() {
      assertThatThrownBy(() -> MemoryEffect.latency(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> MemoryEffect.latency(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
