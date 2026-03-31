/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;
import com.macstab.chaos.toxiproxy.toxic.*;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for TimeoutToxic.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("TimeoutToxic")
class TimeoutToxicTest {

  @Test
  @DisplayName("should create with builder")
  void shouldCreateWithBuilder() {
    TimeoutToxic toxic =
        TimeoutToxic.builder().name("timeout").timeoutMs(5000).toxicity(0.5).build();

    assertThat(toxic.name()).isEqualTo("timeout");
    assertThat(toxic.timeoutMs()).isEqualTo(5000);
    assertThat(toxic.toxicity()).isEqualTo(0.5);
  }

  @Test
  @DisplayName("should use default toxicity (1.0)")
  void shouldUseDefaultToxicity() {
    TimeoutToxic toxic = TimeoutToxic.builder().name("timeout").timeoutMs(5000).build();

    assertThat(toxic.toxicity()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("should allow zero timeout (instant close)")
  void shouldAllowZeroTimeout() {
    TimeoutToxic toxic = TimeoutToxic.builder().name("timeout").timeoutMs(0).build();

    assertThat(toxic.timeoutMs()).isEqualTo(0);
  }

  @Test
  @DisplayName("should fail on negative timeout")
  void shouldFailOnNegativeTimeout() {
    assertThatThrownBy(() -> TimeoutToxic.builder().name("timeout").timeoutMs(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should fail on invalid toxicity (< 0)")
  void shouldFailOnToxicityTooLow() {
    assertThatThrownBy(
            () -> TimeoutToxic.builder().name("timeout").timeoutMs(5000).toxicity(-0.1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should fail on invalid toxicity (> 1)")
  void shouldFailOnToxicityTooHigh() {
    assertThatThrownBy(
            () -> TimeoutToxic.builder().name("timeout").timeoutMs(5000).toxicity(1.1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
