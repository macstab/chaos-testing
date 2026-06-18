/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MemoryPressureInfo}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("MemoryPressureInfo")
class MemoryPressureInfoTest {

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("creates instance with all values accessible")
    void createsInstance() {
      // GIVEN / WHEN
      final var info = new MemoryPressureInfo(1.1, 2.2, 3.3, 4.4, 5.5, 6.6);

      // THEN
      assertThat(info.some10s()).isEqualTo(1.1);
      assertThat(info.some60s()).isEqualTo(2.2);
      assertThat(info.some300s()).isEqualTo(3.3);
      assertThat(info.full10s()).isEqualTo(4.4);
      assertThat(info.full60s()).isEqualTo(5.5);
      assertThat(info.full300s()).isEqualTo(6.6);
    }

    @Test
    @DisplayName("zero-pressure instance is valid")
    void zeroPressureIsValid() {
      final var info = new MemoryPressureInfo(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
      assertThat(info.some10s()).isZero();
      assertThat(info.full300s()).isZero();
    }
  }

  @Nested
  @DisplayName("record contract")
  class RecordContract {

    @Test
    @DisplayName("equals and hashCode are value-based")
    void equalsHashCode() {
      final var a = new MemoryPressureInfo(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
      final var b = new MemoryPressureInfo(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
      assertThat(a).isEqualTo(b);
      assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("toString contains field values")
    void toStringContainsValues() {
      final var info = new MemoryPressureInfo(1.1, 2.2, 3.3, 4.4, 5.5, 6.6);
      assertThat(info.toString())
          .contains("1.1")
          .contains("2.2")
          .contains("3.3")
          .contains("4.4")
          .contains("5.5")
          .contains("6.6");
    }
  }
}
