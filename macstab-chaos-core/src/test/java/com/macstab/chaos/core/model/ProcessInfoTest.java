/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProcessInfo}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ProcessInfo")
class ProcessInfoTest {

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("creates valid instance")
    void createsInstance() {
      final var info = new ProcessInfo(42, "stress-ng");
      assertThat(info.pid()).isEqualTo(42);
      assertThat(info.name()).isEqualTo("stress-ng");
    }

    @Test
    @DisplayName("throws IllegalArgumentException for pid <= 0")
    void throwsForZeroPid() {
      assertThatThrownBy(() -> new ProcessInfo(0, "stress-ng"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("PID must be positive");
    }

    @Test
    @DisplayName("throws IllegalArgumentException for negative pid")
    void throwsForNegativePid() {
      assertThatThrownBy(() -> new ProcessInfo(-1, "stress-ng"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("PID must be positive");
    }

    @Test
    @DisplayName("throws NullPointerException for null name")
    void throwsForNullName() {
      assertThatThrownBy(() -> new ProcessInfo(1, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("name");
    }

    @Test
    @DisplayName("pid=1 (PID 1 init) is valid")
    void pid1IsValid() {
      final var info = new ProcessInfo(1, "redis-server");
      assertThat(info.pid()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("record contract")
  class RecordContract {

    @Test
    @DisplayName("equals and hashCode are value-based")
    void equalsHashCode() {
      final var a = new ProcessInfo(42, "stress-ng");
      final var b = new ProcessInfo(42, "stress-ng");
      assertThat(a).isEqualTo(b);
      assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("toString contains pid and name")
    void toStringContainsFields() {
      final var info = new ProcessInfo(42, "stress-ng");
      assertThat(info.toString()).contains("42").contains("stress-ng");
    }
  }
}
