/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for LimitDataToxic.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LimitDataToxic")
class LimitDataToxicTest {

  @Test
  @DisplayName("should create with builder")
  void shouldCreateWithBuilder() {
    LimitDataToxic toxic = LimitDataToxic.builder().name("limit-data").bytes(1024).build();

    assertThat(toxic.name()).isEqualTo("limit-data");
    assertThat(toxic.bytes()).isEqualTo(1024);
  }

  @Test
  @DisplayName("should fail on null name")
  void shouldFailOnNullName() {
    assertThatThrownBy(() -> LimitDataToxic.builder().bytes(1024).build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should fail on zero bytes")
  void shouldFailOnZeroBytes() {
    assertThatThrownBy(() -> LimitDataToxic.builder().name("limit-data").bytes(0).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should fail on negative bytes")
  void shouldFailOnNegativeBytes() {
    assertThatThrownBy(() -> LimitDataToxic.builder().name("limit-data").bytes(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should allow 1 byte (minimum)")
  void shouldAllow1Byte() {
    LimitDataToxic toxic = LimitDataToxic.builder().name("limit-data").bytes(1).build();

    assertThat(toxic.bytes()).isEqualTo(1);
  }
}
