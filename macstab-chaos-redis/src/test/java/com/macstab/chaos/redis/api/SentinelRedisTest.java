/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SentinelRedis} record.
 */
@DisplayName("SentinelRedis Record")
class SentinelRedisTest {

  private static final Endpoint SENTINEL_1 = new Endpoint("host1", 26379);
  private static final Endpoint SENTINEL_2 = new Endpoint("host2", 26379);
  private static final List<Endpoint> SENTINELS = List.of(SENTINEL_1, SENTINEL_2);
  private static final List<Endpoint> REPLICAS = List.of(new Endpoint("replica1", 6379));

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should create valid record")
    void shouldCreateValid() {
      final SentinelRedis redis =
          new SentinelRedis("localhost", 6379, "mymaster", SENTINELS, REPLICAS);
      assertThat(redis.host()).isEqualTo("localhost");
      assertThat(redis.port()).isEqualTo(6379);
      assertThat(redis.masterName()).isEqualTo("mymaster");
      assertThat(redis.sentinels()).hasSize(2);
      assertThat(redis.replicas()).hasSize(1);
    }

    @Test
    @DisplayName("Should throw for null host")
    void shouldThrowForNullHost() {
      assertThatThrownBy(() -> new SentinelRedis(null, 6379, "master", SENTINELS, REPLICAS))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should throw for null masterName")
    void shouldThrowForNullMasterName() {
      assertThatThrownBy(() -> new SentinelRedis("h", 6379, null, SENTINELS, REPLICAS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Master name");
    }

    @Test
    @DisplayName("Should throw for blank masterName")
    void shouldThrowForBlankMasterName() {
      assertThatThrownBy(() -> new SentinelRedis("h", 6379, " ", SENTINELS, REPLICAS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Master name");
    }

    @Test
    @DisplayName("Should throw for null sentinels")
    void shouldThrowForNullSentinels() {
      assertThatThrownBy(() -> new SentinelRedis("h", 6379, "master", null, REPLICAS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Sentinels");
    }

    @Test
    @DisplayName("Should throw for empty sentinels")
    void shouldThrowForEmptySentinels() {
      assertThatThrownBy(() -> new SentinelRedis("h", 6379, "master", List.of(), REPLICAS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Sentinels");
    }

    @Test
    @DisplayName("Should throw for null replicas")
    void shouldThrowForNullReplicas() {
      assertThatThrownBy(() -> new SentinelRedis("h", 6379, "master", SENTINELS, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Replicas");
    }
  }

  @Nested
  @DisplayName("Defensive copy")
  class DefensiveCopy {

    @Test
    @DisplayName("Should make defensive copy of sentinels list")
    void shouldDefensiveCopySentinels() {
      // ARRANGE
      final List<Endpoint> mutable = new ArrayList<>(SENTINELS);
      final SentinelRedis redis = new SentinelRedis("h", 6379, "master", mutable, REPLICAS);

      // ACT: Mutate original list
      mutable.clear();

      // ASSERT: Record's sentinels are unaffected
      assertThat(redis.sentinels()).hasSize(2);
    }

    @Test
    @DisplayName("Should make defensive copy of replicas list")
    void shouldDefensiveCopyReplicas() {
      final List<Endpoint> mutable = new ArrayList<>(REPLICAS);
      final SentinelRedis redis = new SentinelRedis("h", 6379, "master", SENTINELS, mutable);
      mutable.clear();
      assertThat(redis.replicas()).hasSize(1);
    }

    @Test
    @DisplayName("Sentinels should be immutable")
    void sentinelsShouldBeImmutable() {
      final SentinelRedis redis = new SentinelRedis("h", 6379, "master", SENTINELS, REPLICAS);
      assertThatThrownBy(() -> redis.sentinels().add(SENTINEL_1))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("Interface methods")
  class InterfaceMethods {

    @Test
    @DisplayName("getTopology() should return SENTINEL")
    void shouldReturnSentinelTopology() {
      final SentinelRedis redis = new SentinelRedis("h", 6379, "master", SENTINELS, REPLICAS);
      assertThat(redis.getTopology()).isEqualTo(RedisTopology.SENTINEL);
    }

    @Test
    @DisplayName("getHost() and getPort() should return record components")
    void shouldReturnHostAndPort() {
      final SentinelRedis redis = new SentinelRedis("myhost", 26380, "master", SENTINELS, REPLICAS);
      assertThat(redis.getHost()).isEqualTo("myhost");
      assertThat(redis.getPort()).isEqualTo(26380);
    }
  }
}
