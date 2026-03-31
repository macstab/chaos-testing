/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;
import com.macstab.chaos.toxiproxy.toxic.*;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for LatencyToxic.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LatencyToxic")
class LatencyToxicTest {

  @Test
  @DisplayName("should create with builder")
  void shouldCreateWithBuilder() {
    LatencyToxic toxic =
        LatencyToxic.builder().name("latency").latencyMs(500).jitterMs(100).build();

    assertThat(toxic.name()).isEqualTo("latency");
    assertThat(toxic.latencyMs()).isEqualTo(500);
    assertThat(toxic.jitterMs()).isEqualTo(100);
  }

  @Test
  @DisplayName("should use default jitter (0)")
  void shouldUseDefaultJitter() {
    LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(500).build();

    assertThat(toxic.jitterMs()).isEqualTo(0);
  }

  @Test
  @DisplayName("should fail on null name")
  void shouldFailOnNullName() {
    assertThatThrownBy(() -> LatencyToxic.builder().latencyMs(500).build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should fail on negative latency")
  void shouldFailOnNegativeLatency() {
    assertThatThrownBy(() -> LatencyToxic.builder().name("latency").latencyMs(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should fail on negative jitter")
  void shouldFailOnNegativeJitter() {
    assertThatThrownBy(
            () -> LatencyToxic.builder().name("latency").latencyMs(500).jitterMs(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should allow zero latency")
  void shouldAllowZeroLatency() {
    LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(0).build();

    assertThat(toxic.latencyMs()).isEqualTo(0);
  }
}
