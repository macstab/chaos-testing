/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolPackage}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ToolPackage")
class ToolPackageTest {

  @Nested
  @DisplayName("of()")
  class OfFactory {

    @Test
    @DisplayName("creates instance with distinct tool and package names")
    void distinctNames() {
      final var tp = ToolPackage.of("taskset", "util-linux");
      assertThat(tp.tool()).isEqualTo("taskset");
      assertThat(tp.packageName()).isEqualTo("util-linux");
    }

    @Test
    @DisplayName("throws NullPointerException for null tool")
    void nullTool() {
      assertThatThrownBy(() -> ToolPackage.of(null, "util-linux"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("tool");
    }

    @Test
    @DisplayName("throws NullPointerException for null packageName")
    void nullPackage() {
      assertThatThrownBy(() -> ToolPackage.of("taskset", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("packageName");
    }

    @Test
    @DisplayName("throws IllegalArgumentException for blank tool")
    void blankTool() {
      assertThatThrownBy(() -> ToolPackage.of("  ", "util-linux"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("tool");
    }

    @Test
    @DisplayName("throws IllegalArgumentException for blank packageName")
    void blankPackage() {
      assertThatThrownBy(() -> ToolPackage.of("taskset", ""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("packageName");
    }
  }

  @Nested
  @DisplayName("ofSame()")
  class OfSameFactory {

    @Test
    @DisplayName("tool and packageName are equal")
    void sameNames() {
      final var tp = ToolPackage.ofSame("stress-ng");
      assertThat(tp.tool()).isEqualTo("stress-ng");
      assertThat(tp.packageName()).isEqualTo("stress-ng");
    }

    @Test
    @DisplayName("throws NullPointerException for null input")
    void nullInput() {
      assertThatThrownBy(() -> ToolPackage.ofSame(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("record contract")
  class RecordContract {

    @Test
    @DisplayName("equals and hashCode are value-based")
    void equalsHashCode() {
      final var a = ToolPackage.of("taskset", "util-linux");
      final var b = ToolPackage.of("taskset", "util-linux");
      assertThat(a).isEqualTo(b);
      assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("toString contains both fields")
    void toStringContainsFields() {
      final var tp = ToolPackage.of("taskset", "util-linux");
      assertThat(tp.toString()).contains("taskset").contains("util-linux");
    }
  }
}
