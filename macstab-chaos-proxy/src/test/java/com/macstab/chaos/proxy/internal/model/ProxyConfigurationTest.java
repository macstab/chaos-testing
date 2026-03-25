/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.model;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for ProxyConfiguration.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ProxyConfiguration")
class ProxyConfigurationTest {

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {

    @Test
    @DisplayName("should create valid configuration")
    void shouldCreateValidConfiguration() {
      ProxyConfiguration config = new ProxyConfiguration("redis", 6379, 16379);

      assertThat(config.name()).isEqualTo("redis");
      assertThat(config.servicePort()).isEqualTo(6379);
      assertThat(config.proxyPort()).isEqualTo(16379);
    }

    @Test
    @DisplayName("should fail on null name")
    void shouldFailOnNullName() {
      assertThatThrownBy(() -> new ProxyConfiguration(null, 6379, 16379))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("name");
    }

    @Test
    @DisplayName("should fail on empty name")
    void shouldFailOnEmptyName() {
      assertThatThrownBy(() -> new ProxyConfiguration("", 6379, 16379))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("name");
    }

    @Test
    @DisplayName("should fail on invalid servicePort (too low)")
    void shouldFailOnInvalidServicePortTooLow() {
      assertThatThrownBy(() -> new ProxyConfiguration("redis", 0, 16379))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("servicePort");
    }

    @Test
    @DisplayName("should fail on invalid servicePort (too high)")
    void shouldFailOnInvalidServicePortTooHigh() {
      assertThatThrownBy(() -> new ProxyConfiguration("redis", 65536, 16379))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("servicePort");
    }

    @Test
    @DisplayName("should fail on invalid proxyPort (too low)")
    void shouldFailOnInvalidProxyPortTooLow() {
      assertThatThrownBy(() -> new ProxyConfiguration("redis", 6379, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("proxyPort");
    }

    @Test
    @DisplayName("should fail on invalid proxyPort (too high)")
    void shouldFailOnInvalidProxyPortTooHigh() {
      assertThatThrownBy(() -> new ProxyConfiguration("redis", 6379, 65536))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("proxyPort");
    }

    @Test
    @DisplayName("should allow port 1 (minimum valid)")
    void shouldAllowPort1() {
      ProxyConfiguration config = new ProxyConfiguration("test", 1, 2);

      assertThat(config.servicePort()).isEqualTo(1);
      assertThat(config.proxyPort()).isEqualTo(2);
    }

    @Test
    @DisplayName("should allow port 65535 (maximum valid)")
    void shouldAllowPort65535() {
      ProxyConfiguration config = new ProxyConfiguration("test", 65535, 65534);

      assertThat(config.servicePort()).isEqualTo(65535);
      assertThat(config.proxyPort()).isEqualTo(65534);
    }

    @Test
    @DisplayName("should allow same service and proxy port")
    void shouldAllowSameServiceAndProxyPort() {
      // Not recommended, but technically valid
      ProxyConfiguration config = new ProxyConfiguration("test", 6379, 6379);

      assertThat(config.servicePort()).isEqualTo(6379);
      assertThat(config.proxyPort()).isEqualTo(6379);
    }
  }

  @Nested
  @DisplayName("Equals and HashCode")
  class EqualsHashCodeTests {

    @Test
    @DisplayName("should be equal with same values")
    void shouldBeEqualWithSameValues() {
      ProxyConfiguration config1 = new ProxyConfiguration("redis", 6379, 16379);
      ProxyConfiguration config2 = new ProxyConfiguration("redis", 6379, 16379);

      assertThat(config1).isEqualTo(config2);
      assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    @DisplayName("should not be equal with different name")
    void shouldNotBeEqualWithDifferentName() {
      ProxyConfiguration config1 = new ProxyConfiguration("redis", 6379, 16379);
      ProxyConfiguration config2 = new ProxyConfiguration("postgres", 6379, 16379);

      assertThat(config1).isNotEqualTo(config2);
    }

    @Test
    @DisplayName("should not be equal with different servicePort")
    void shouldNotBeEqualWithDifferentServicePort() {
      ProxyConfiguration config1 = new ProxyConfiguration("redis", 6379, 16379);
      ProxyConfiguration config2 = new ProxyConfiguration("redis", 5432, 16379);

      assertThat(config1).isNotEqualTo(config2);
    }

    @Test
    @DisplayName("should not be equal with different proxyPort")
    void shouldNotBeEqualWithDifferentProxyPort() {
      ProxyConfiguration config1 = new ProxyConfiguration("redis", 6379, 16379);
      ProxyConfiguration config2 = new ProxyConfiguration("redis", 6379, 17379);

      assertThat(config1).isNotEqualTo(config2);
    }
  }

  @Nested
  @DisplayName("ToString")
  class ToStringTests {

    @Test
    @DisplayName("should include all fields in toString")
    void shouldIncludeAllFieldsInToString() {
      ProxyConfiguration config = new ProxyConfiguration("redis", 6379, 16379);

      String toString = config.toString();

      assertThat(toString)
          .contains("name")
          .contains("redis")
          .contains("servicePort")
          .contains("6379")
          .contains("proxyPort")
          .contains("16379");
    }
  }
}
