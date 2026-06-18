/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuleHandle (unit)")
class RuleHandleTest {

  @Test
  @DisplayName("accepts lowercase letters, digits, underscores")
  void accepts() {
    assertThat(new RuleHandle("r1").owner()).isEqualTo("r1");
    assertThat(new RuleHandle("foo_bar_99").owner()).isEqualTo("foo_bar_99");
  }

  @Test
  @DisplayName("rejects null / empty / uppercase / punctuation")
  void rejects() {
    assertThatThrownBy(() -> new RuleHandle(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RuleHandle("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuleHandle("R1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuleHandle("r-1")).isInstanceOf(IllegalArgumentException.class);
  }
}
