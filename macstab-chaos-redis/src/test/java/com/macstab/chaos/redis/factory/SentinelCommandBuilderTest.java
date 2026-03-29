/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SentinelCommandBuilder}.
 */
@DisplayName("SentinelCommandBuilder")
class SentinelCommandBuilderTest {

  @Nested
  @DisplayName("buildSentinelCommandWithoutAnnounce()")
  class BuildSentinelCommand {

    @Test
    @DisplayName("Should include master IP and quorum in command")
    void shouldIncludeMasterIpAndQuorum() {
      // ARRANGE & ACT
      final String cmd = SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce("172.18.0.2", 2);

      // ASSERT
      assertThat(cmd).contains("172.18.0.2");
      assertThat(cmd).contains("2");
      assertThat(cmd).contains("sentinel monitor mymaster");
      assertThat(cmd).contains("redis-server");
      assertThat(cmd).contains("--sentinel");
    }

    @Test
    @DisplayName("Should configure sentinel port 26379")
    void shouldConfigureSentinelPort() {
      final String cmd = SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce("10.0.0.1", 1);
      assertThat(cmd).contains("port 26379");
    }

    @Test
    @DisplayName("Should configure down-after-milliseconds")
    void shouldConfigureDownAfterMilliseconds() {
      final String cmd = SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce("10.0.0.1", 2);
      assertThat(cmd).contains("sentinel down-after-milliseconds");
    }

    @Test
    @DisplayName("Should configure failover-timeout")
    void shouldConfigureFailoverTimeout() {
      final String cmd = SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce("10.0.0.1", 2);
      assertThat(cmd).contains("sentinel failover-timeout");
    }

    @Test
    @DisplayName("Should use quorum=1 for single sentinel")
    void shouldUseSingleSentinelQuorum() {
      final String cmd = SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce("127.0.0.1", 1);
      assertThat(cmd).contains("127.0.0.1 6379 1");
    }
  }
}
