/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SentinelCommandBuilder}. */
@DisplayName("SentinelCommandBuilder")
class SentinelCommandBuilderTest {

  @Nested
  @DisplayName("buildSentinelCommandWithoutAnnounce()")
  class BuildSentinelCommand {

    @Test
    @DisplayName("Should include master IP and quorum in command")
    void shouldIncludeMasterIpAndQuorum() {
      // ARRANGE & ACT
      final String cmd =
          SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce("172.18.0.2", 2);

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

    @Test
    @DisplayName("quorum=2 should embed ' 6379 2' in command")
    void shouldEmbedQuorumTwo() {
      final String cmd = SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce("10.0.0.1", 2);
      assertThat(cmd).contains("6379 2");
    }

    @Test
    @DisplayName("quorum=3 should embed ' 6379 3' in command")
    void shouldEmbedQuorumThree() {
      final String cmd = SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce("10.0.0.2", 3);
      assertThat(cmd).contains("6379 3");
    }

    @Test
    @DisplayName("masterIp=null should throw NPE")
    void shouldThrowNpeForNullMasterIp() {
      assertThatThrownBy(() -> SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce(null, 2))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
