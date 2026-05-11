/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuleHandle (unit)")
class RuleHandleTest {

  @Test
  @DisplayName("accepts lowercase, digit, underscore")
  void accepts() {
    assertThat(new RuleHandle("r1").owner()).isEqualTo("r1");
    assertThat(new RuleHandle("r_42").owner()).isEqualTo("r_42");
    assertThat(new RuleHandle("foo_bar_99").owner()).isEqualTo("foo_bar_99");
  }

  @Test
  @DisplayName("rejects null")
  void rejectsNull() {
    assertThatThrownBy(() -> new RuleHandle(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("rejects empty")
  void rejectsEmpty() {
    assertThatThrownBy(() -> new RuleHandle(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("[a-z0-9_]+");
  }

  @Test
  @DisplayName("rejects uppercase")
  void rejectsUpper() {
    assertThatThrownBy(() -> new RuleHandle("R1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects dash and dot")
  void rejectsPunctuation() {
    assertThatThrownBy(() -> new RuleHandle("r-1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuleHandle("r.1")).isInstanceOf(IllegalArgumentException.class);
  }
}
