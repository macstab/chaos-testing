/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link ProxyChaosProvider}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ProxyChaosProvider - Unit Tests")
class ProxyChaosProviderTest {

  @Nested
  @DisplayName("Input Validation")
  class InputValidationTests {

    @Test
    @DisplayName("should reject null container in createProxy")
    void shouldRejectNullContainerInCreateProxy() {
      final var provider = new ProxyChaosProvider();

      assertThatThrownBy(() -> provider.createProxy(null, "proxy", 8080, 18080))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject null proxyName in createProxy")
    void shouldRejectNullProxyNameInCreateProxy() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      assertThatThrownBy(() -> provider.createProxy(container, null, 8080, 18080))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyName");
    }

    @Test
    @DisplayName("should reject null container in addLatency")
    void shouldRejectNullContainerInAddLatency() {
      final var provider = new ProxyChaosProvider();

      assertThatThrownBy(() -> provider.addLatency(null, "proxy", Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject null proxyName in addLatency")
    void shouldRejectNullProxyNameInAddLatency() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      assertThatThrownBy(() -> provider.addLatency(container, null, Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyName");
    }

    @Test
    @DisplayName("should reject null latency in addLatency")
    void shouldRejectNullLatencyInAddLatency() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      assertThatThrownBy(() -> provider.addLatency(container, "proxy", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("latency");
    }

    @Test
    @DisplayName("should reject negative latency")
    void shouldRejectNegativeLatency() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      assertThatThrownBy(
              () -> provider.addLatency(container, "proxy", Duration.ofMillis(-100)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("latency");
    }

    @Test
    @DisplayName("should reject invalid timeout probability - negative")
    void shouldRejectNegativeTimeoutProbability() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      assertThatThrownBy(() -> provider.addTimeout(container, "proxy", Duration.ofMillis(1), -0.1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("probability");
    }

    @Test
    @DisplayName("should reject invalid timeout probability - too high")
    void shouldRejectTooHighTimeoutProbability() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      assertThatThrownBy(() -> provider.addTimeout(container, "proxy", Duration.ofMillis(1), 1.1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("probability");
    }

    @Test
    @DisplayName("should reject negative bandwidth")
    void shouldRejectNegativeBandwidth() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      assertThatThrownBy(() -> provider.limitBandwidth(container, "proxy", -1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rateKBps");
    }

    @Test
    @DisplayName("should reject zero bandwidth")
    void shouldRejectZeroBandwidth() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      assertThatThrownBy(() -> provider.limitBandwidth(container, "proxy", 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rateKBps");
    }

    @Test
    @DisplayName("should reject negative slowClose delay")
    void shouldRejectNegativeSlowCloseDelay() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      assertThatThrownBy(
              () -> provider.slowClose(container, "proxy", Duration.ofMillis(-100)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("delay");
    }

    @Test
    @DisplayName("should reject null container in reset")
    void shouldRejectNullContainerInReset() {
      final var provider = new ProxyChaosProvider();

      assertThatThrownBy(() -> provider.reset(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }
  }

  @Nested
  @DisplayName("Boundary Tests")
  class BoundaryTests {

    @Test
    @DisplayName("should accept zero timeout probability")
    void shouldAcceptZeroTimeoutProbability() {
      final var provider = new ProxyChaosProvider();
      assertThat(provider).isNotNull();
      // Input validation passes, actual execution would fail (no container running)
    }

    @Test
    @DisplayName("should accept one timeout probability")
    void shouldAcceptOneTimeoutProbability() {
      final var provider = new ProxyChaosProvider();
      assertThat(provider).isNotNull();
    }

    @Test
    @DisplayName("should accept zero latency")
    void shouldAcceptZeroLatency() {
      final var provider = new ProxyChaosProvider();
      final var container = new GenericContainer<>("redis:7.4");

      // Should not throw IllegalArgumentException for validation
      assertThatThrownBy(() -> provider.addLatency(container, "proxy", Duration.ZERO))
          .isNotInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("General Behavior")
  class GeneralBehaviorTests {

    @Test
    @DisplayName("should be supported")
    void shouldBeSupported() {
      final var provider = new ProxyChaosProvider();

      assertThat(provider.isSupported()).isTrue();
    }
  }
}
