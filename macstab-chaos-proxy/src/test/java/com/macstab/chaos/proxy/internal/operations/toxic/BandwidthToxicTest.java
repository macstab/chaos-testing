/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations.toxic;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.toxiproxy.toxic.*;

/**
 * Tests for BandwidthToxic.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("BandwidthToxic")
class BandwidthToxicTest {

  @Test
  @DisplayName("should create with builder")
  void shouldCreateWithBuilder() {
    BandwidthToxic toxic = BandwidthToxic.builder().name("bandwidth").rateKbps(100).build();

    assertThat(toxic.name()).isEqualTo("bandwidth");
    assertThat(toxic.rateKbps()).isEqualTo(100);
  }

  @Test
  @DisplayName("should fail on null name")
  void shouldFailOnNullName() {
    assertThatThrownBy(() -> BandwidthToxic.builder().rateKbps(100).build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("should fail on zero rate")
  void shouldFailOnZeroRate() {
    assertThatThrownBy(() -> BandwidthToxic.builder().name("bandwidth").rateKbps(0).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should fail on negative rate")
  void shouldFailOnNegativeRate() {
    assertThatThrownBy(() -> BandwidthToxic.builder().name("bandwidth").rateKbps(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should allow rate 1 KB/s (minimum)")
  void shouldAllowRate1() {
    BandwidthToxic toxic = BandwidthToxic.builder().name("bandwidth").rateKbps(1).build();

    assertThat(toxic.rateKbps()).isEqualTo(1);
  }
}
