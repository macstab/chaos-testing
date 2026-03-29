/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StandaloneRedis} record.
 */
@DisplayName("StandaloneRedis Record")
class StandaloneRedisTest {

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should create valid record with host and port")
    void shouldCreateValidRecord() {
      // ARRANGE & ACT
      final StandaloneRedis redis = new StandaloneRedis("localhost", 6379);

      // ASSERT
      assertThat(redis.host()).isEqualTo("localhost");
      assertThat(redis.port()).isEqualTo(6379);
    }

    @Test
    @DisplayName("Should throw for null host")
    void shouldThrowForNullHost() {
      assertThatThrownBy(() -> new StandaloneRedis(null, 6379))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Host cannot be null or blank");
    }

    @Test
    @DisplayName("Should throw for blank host")
    void shouldThrowForBlankHost() {
      assertThatThrownBy(() -> new StandaloneRedis("  ", 6379))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Host cannot be null or blank");
    }

    @Test
    @DisplayName("Should throw for port below 1")
    void shouldThrowForPortBelowOne() {
      assertThatThrownBy(() -> new StandaloneRedis("localhost", 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Port must be between 1 and 65535");
    }

    @Test
    @DisplayName("Should throw for port above 65535")
    void shouldThrowForPortAbove65535() {
      assertThatThrownBy(() -> new StandaloneRedis("localhost", 65536))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Port must be between 1 and 65535");
    }

    @Test
    @DisplayName("Should accept port at boundary values (1 and 65535)")
    void shouldAcceptBoundaryPorts() {
      assertThat(new StandaloneRedis("h", 1).port()).isEqualTo(1);
      assertThat(new StandaloneRedis("h", 65535).port()).isEqualTo(65535);
    }
  }

  @Nested
  @DisplayName("Interface methods")
  class InterfaceMethods {

    @Test
    @DisplayName("getHost() should delegate to record component")
    void shouldDelegateGetHost() {
      final StandaloneRedis redis = new StandaloneRedis("myhost", 6380);
      assertThat(redis.getHost()).isEqualTo("myhost");
    }

    @Test
    @DisplayName("getPort() should delegate to record component")
    void shouldDelegateGetPort() {
      final StandaloneRedis redis = new StandaloneRedis("myhost", 6380);
      assertThat(redis.getPort()).isEqualTo(6380);
    }

    @Test
    @DisplayName("getTopology() should return STANDALONE")
    void shouldReturnStandaloneTopology() {
      final StandaloneRedis redis = new StandaloneRedis("localhost", 6379);
      assertThat(redis.getTopology()).isEqualTo(RedisTopology.STANDALONE);
    }
  }

  @Nested
  @DisplayName("Record semantics")
  class RecordSemantics {

    @Test
    @DisplayName("equals() should be value-based")
    void shouldHaveValueBasedEquals() {
      final StandaloneRedis a = new StandaloneRedis("localhost", 6379);
      final StandaloneRedis b = new StandaloneRedis("localhost", 6379);
      assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("hashCode() should be consistent with equals()")
    void shouldHaveConsistentHashCode() {
      final StandaloneRedis a = new StandaloneRedis("localhost", 6379);
      final StandaloneRedis b = new StandaloneRedis("localhost", 6379);
      assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("toString() should include host and port")
    void shouldHaveMeaningfulToString() {
      final StandaloneRedis redis = new StandaloneRedis("localhost", 6379);
      assertThat(redis.toString()).contains("localhost").contains("6379");
    }

    @Test
    @DisplayName("Should be sealed permits member")
    void shouldBeRedisSubtype() {
      final Redis redis = new StandaloneRedis("localhost", 6379);
      assertThat(redis).isInstanceOf(StandaloneRedis.class);
    }
  }
}
