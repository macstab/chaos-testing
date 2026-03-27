/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for ToxiproxyConfig.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ToxiproxyConfig")
class ToxiproxyConfigTest {

  @Nested
  @DisplayName("defaults()")
  class DefaultsTests {

    @Test
    @DisplayName("should create default configuration")
    void shouldCreateDefaults() {
      ToxiproxyConfig config = ToxiproxyConfig.defaults();

      assertThat(config.apiUrl()).isEqualTo("http://localhost:8474");
      assertThat(config.startupTimeoutMs()).isEqualTo(10000);
      assertThat(config.pollIntervalMs()).isEqualTo(100);
      assertThat(config.proxyReadyTimeoutMs()).isEqualTo(2000);
      assertThat(config.connectionTimeoutMs()).isEqualTo(5000);
      assertThat(config.readTimeoutMs()).isEqualTo(5000);
    }
  }

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {

    @Test
    @DisplayName("should create config with all fields")
    void shouldCreateWithAllFields() {
      ToxiproxyConfig config =
          ToxiproxyConfig.builder()
              .apiUrl("http://localhost:9999")
              .startupTimeoutMs(5000)
              .pollIntervalMs(50)
              .proxyReadyTimeoutMs(1000)
              .connectionTimeoutMs(3000)
              .readTimeoutMs(3000)
              .build();

      assertThat(config.apiUrl()).isEqualTo("http://localhost:9999");
      assertThat(config.startupTimeoutMs()).isEqualTo(5000);
      assertThat(config.pollIntervalMs()).isEqualTo(50);
      assertThat(config.proxyReadyTimeoutMs()).isEqualTo(1000);
      assertThat(config.connectionTimeoutMs()).isEqualTo(3000);
      assertThat(config.readTimeoutMs()).isEqualTo(3000);
    }

    @Test
    @DisplayName("should fail on null apiUrl")
    void shouldFailOnNullApiUrl() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().apiUrl(null).build())
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should allow empty apiUrl (validated at connection time)")
    void shouldAllowEmptyApiUrl() {
      // Empty URL is accepted at construction — connection errors occur at use time
      ToxiproxyConfig config = ToxiproxyConfig.builder().apiUrl("").build();
      assertThat(config.apiUrl()).isEmpty();
    }

    @Test
    @DisplayName("should fail on negative startupTimeoutMs")
    void shouldFailOnNegativeStartupTimeout() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().startupTimeoutMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should fail on zero startupTimeoutMs")
    void shouldFailOnZeroStartupTimeout() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().startupTimeoutMs(0).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should fail on negative pollIntervalMs")
    void shouldFailOnNegativePollInterval() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().pollIntervalMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should fail on zero pollIntervalMs")
    void shouldFailOnZeroPollInterval() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().pollIntervalMs(0).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should fail on negative proxyReadyTimeoutMs")
    void shouldFailOnNegativeProxyReadyTimeout() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().proxyReadyTimeoutMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should fail on negative connectionTimeoutMs")
    void shouldFailOnNegativeConnectionTimeout() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().connectionTimeoutMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should fail on negative readTimeoutMs")
    void shouldFailOnNegativeReadTimeout() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().readTimeoutMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should fail on zero proxyReadyTimeoutMs")
    void shouldFailOnZeroProxyReadyTimeout() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().proxyReadyTimeoutMs(0).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("proxyReadyTimeoutMs");
    }

    @Test
    @DisplayName("should fail on zero connectionTimeoutMs")
    void shouldFailOnZeroConnectionTimeout() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().connectionTimeoutMs(0).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("connectionTimeoutMs");
    }

    @Test
    @DisplayName("should fail on zero readTimeoutMs")
    void shouldFailOnZeroReadTimeout() {
      assertThatThrownBy(() -> ToxiproxyConfig.builder().readTimeoutMs(0).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("readTimeoutMs");
    }
  }

  @Nested
  @DisplayName("Equals and HashCode")
  class EqualsHashCodeTests {

    @Test
    @DisplayName("same instance should be equal to itself")
    void sameInstance_isEqualToItself() {
      ToxiproxyConfig config = ToxiproxyConfig.defaults();

      assertThat(config).isEqualTo(config);
      assertThat(config.hashCode()).isEqualTo(config.hashCode());
    }

    @Test
    @DisplayName("should not be equal with different values")
    void shouldNotBeEqualWithDifferentValues() {
      ToxiproxyConfig config1 = ToxiproxyConfig.defaults();
      ToxiproxyConfig config2 = ToxiproxyConfig.builder().apiUrl("http://localhost:9999").build();

      assertThat(config1).isNotEqualTo(config2);
    }
  }

  @Nested
  @DisplayName("ToString")
  class ToStringTests {

    @Test
    @DisplayName("toString returns non-null non-empty string")
    void toString_isNonEmpty() {
      ToxiproxyConfig config = ToxiproxyConfig.defaults();

      assertThat(config.toString()).isNotNull().isNotEmpty();
    }
  }
}
