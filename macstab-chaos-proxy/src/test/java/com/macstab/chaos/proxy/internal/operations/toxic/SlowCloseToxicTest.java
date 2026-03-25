/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for SlowCloseToxic.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("SlowCloseToxic")
class SlowCloseToxicTest {

  @Test
  @DisplayName("should create with builder")
  void shouldCreateWithBuilder() {
    SlowCloseToxic toxic = SlowCloseToxic.builder().name("slow-close").delayMs(5000).build();

    assertThat(toxic.name()).isEqualTo("slow-close");
    assertThat(toxic.delayMs()).isEqualTo(5000);
  }

  @Test
  @DisplayName("should fail on null name")
  void shouldFailOnNullName() {
    assertThatThrownBy(() -> SlowCloseToxic.builder().delayMs(5000).build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should fail on negative delay")
  void shouldFailOnNegativeDelay() {
    assertThatThrownBy(() -> SlowCloseToxic.builder().name("slow-close").delayMs(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should allow zero delay")
  void shouldAllowZeroDelay() {
    SlowCloseToxic toxic = SlowCloseToxic.builder().name("slow-close").delayMs(0).build();

    assertThat(toxic.delayMs()).isEqualTo(0);
  }
}
