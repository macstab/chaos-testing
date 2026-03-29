/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.cache.redis.internal.RedisCommandBuilder;

/**
 * Unit tests for {@link RedisCommandBuilder}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("RedisCommandBuilder")
class RedisCommandBuilderTest {

  @Nested
  @DisplayName("buildForceEvictionCommand")
  class ForceEvictionCommandTests {

    @Test
    @DisplayName("should include redis port and percentage")
    void shouldIncludePortAndPercentage() {
      final String cmd = RedisCommandBuilder.buildForceEvictionCommand(6379, 50);
      assertThat(cmd).contains("6379").contains("50");
    }

    @Test
    @DisplayName("should use scan pipeline pattern")
    void shouldUseScanPipeline() {
      final String cmd = RedisCommandBuilder.buildForceEvictionCommand(6379, 50);
      assertThat(cmd).contains("--scan").contains("head -n").contains("DEL");
    }

    @Test
    @DisplayName("should use custom port")
    void shouldUseCustomPort() {
      final String cmd = RedisCommandBuilder.buildForceEvictionCommand(6380, 25);
      assertThat(cmd).contains("6380").contains("25");
    }
  }

  @Nested
  @DisplayName("buildSetMemoryLimitCommand")
  class SetMemoryLimitCommandTests {

    @Test
    @DisplayName("should include CONFIG SET maxmemory")
    void shouldIncludeConfigSet() {
      final String cmd = RedisCommandBuilder.buildSetMemoryLimitCommand(6379, 67108864L);
      assertThat(cmd).contains("CONFIG SET maxmemory").contains("67108864").contains("6379");
    }

    @Test
    @DisplayName("should support zero (remove limit)")
    void shouldSupportZero() {
      final String cmd = RedisCommandBuilder.buildSetMemoryLimitCommand(6379, 0L);
      assertThat(cmd).contains("maxmemory 0");
    }
  }

  @Nested
  @DisplayName("buildSetEvictionPolicyCommand")
  class SetEvictionPolicyCommandTests {

    @Test
    @DisplayName("should include CONFIG SET maxmemory-policy")
    void shouldIncludeConfigSet() {
      final String cmd = RedisCommandBuilder.buildSetEvictionPolicyCommand(6379, "allkeys-lru");
      assertThat(cmd)
          .contains("CONFIG SET maxmemory-policy")
          .contains("allkeys-lru")
          .contains("6379");
    }

    @Test
    @DisplayName("should support noeviction policy")
    void shouldSupportNoEviction() {
      final String cmd = RedisCommandBuilder.buildSetEvictionPolicyCommand(6379, "noeviction");
      assertThat(cmd).contains("noeviction");
    }
  }

  @Nested
  @DisplayName("buildDisconnectClientsCommand")
  class DisconnectClientsCommandTests {

    @Test
    @DisplayName("should include CLIENT LIST and CLIENT KILL ID")
    void shouldIncludeClientKill() {
      final String cmd = RedisCommandBuilder.buildDisconnectClientsCommand(6379);
      assertThat(cmd).contains("CLIENT LIST").contains("CLIENT KILL ID").contains("6379");
    }

    @Test
    @DisplayName("should use custom port")
    void shouldUseCustomPort() {
      final String cmd = RedisCommandBuilder.buildDisconnectClientsCommand(6380);
      assertThat(cmd).contains("6380");
    }
  }

  @Nested
  @DisplayName("buildFlushAllCommand")
  class FlushAllCommandTests {

    @Test
    @DisplayName("should include FLUSHALL and port")
    void shouldIncludeFlushAll() {
      final String cmd = RedisCommandBuilder.buildFlushAllCommand(6379);
      assertThat(cmd).contains("FLUSHALL").contains("6379");
    }
  }

  @Nested
  @DisplayName("buildDbSizeCommand")
  class DbSizeCommandTests {

    @Test
    @DisplayName("should include DBSIZE and port")
    void shouldIncludeDbSize() {
      final String cmd = RedisCommandBuilder.buildDbSizeCommand(6379);
      assertThat(cmd).contains("DBSIZE").contains("6379");
    }
  }
}
