/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RedisCommandBuilder}.
 *
 * <p>Pure string assertions — no Docker required.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("RedisCommandBuilder")
class RedisCommandBuilderTest {

  @Nested
  @DisplayName("buildRoleCommand()")
  class BuildRoleCommand {

    @Test
    @DisplayName("Should include redis-cli, port and ROLE")
    void shouldBuildRoleCommand() {
      final String cmd = RedisCommandBuilder.buildRoleCommand(6379);
      assertThat(cmd).contains("redis-cli -p 6379 ROLE");
    }

    @Test
    @DisplayName("Should use given port in command")
    void shouldUseSentinelPort() {
      final String cmd = RedisCommandBuilder.buildRoleCommand(26379);
      assertThat(cmd).contains("redis-cli -p 26379 ROLE");
    }
  }

  @Nested
  @DisplayName("buildPingCommand()")
  class BuildPingCommand {

    @Test
    @DisplayName("Should include PING in command")
    void shouldContainPing() {
      final String cmd = RedisCommandBuilder.buildPingCommand(6379);
      assertThat(cmd).contains("PING");
      assertThat(cmd).contains("6379");
    }
  }

  @Nested
  @DisplayName("buildConfigSet()")
  class BuildConfigSet {

    @Test
    @DisplayName("Should include CONFIG SET with key and value")
    void shouldBuildConfigSet() {
      final String cmd = RedisCommandBuilder.buildConfigSet(6379, "maxmemory", "64mb");
      assertThat(cmd).contains("CONFIG SET maxmemory 64mb");
      assertThat(cmd).contains("6379");
    }

    @Test
    @DisplayName("Should throw NPE for null key")
    void shouldThrowOnNullKey() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildConfigSet(6379, null, "64mb"));
    }

    @Test
    @DisplayName("Should throw NPE for null value")
    void shouldThrowOnNullValue() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildConfigSet(6379, "maxmemory", null));
    }
  }

  @Nested
  @DisplayName("buildSentinelMasterCommand()")
  class BuildSentinelMasterCommand {

    @Test
    @DisplayName("Should include SENTINEL master and master name")
    void shouldBuildSentinelMasterCommand() {
      final String cmd = RedisCommandBuilder.buildSentinelMasterCommand("mymaster");
      assertThat(cmd).contains("SENTINEL master mymaster");
    }

    @Test
    @DisplayName("Should throw NPE for null masterName")
    void shouldThrowOnNullMasterName() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSentinelMasterCommand(null));
    }
  }

  @Nested
  @DisplayName("buildSentinelStartCommand()")
  class BuildSentinelStartCommand {

    @Test
    @DisplayName("Should include masterIp and quorum")
    void shouldIncludeMasterIpAndQuorum() {
      final String cmd = RedisCommandBuilder.buildSentinelStartCommand("172.17.0.2", 2);
      assertThat(cmd).contains("172.17.0.2");
      assertThat(cmd).contains("172.17.0.2 6379 2");
      assertThat(cmd).contains("sentinel monitor mymaster");
      assertThat(cmd).contains("redis-server");
      assertThat(cmd).contains("--sentinel");
    }

    @Test
    @DisplayName("Should throw NPE for null masterIp")
    void shouldThrowOnNullMasterIp() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSentinelStartCommand(null, 2));
    }
  }

  @Nested
  @DisplayName("buildAnnounceIpCommand()")
  class BuildAnnounceIpCommand {

    @Test
    @DisplayName("Should build replica-announce-ip command")
    void shouldBuildAnnounceIpCommand() {
      final String cmd =
          RedisCommandBuilder.buildAnnounceIpCommand(6379, "host.testcontainers.internal");
      assertThat(cmd).contains("replica-announce-ip");
      assertThat(cmd).contains("host.testcontainers.internal");
      assertThat(cmd).contains("6379");
    }

    @Test
    @DisplayName("Should throw NPE for null announceHost")
    void shouldThrowOnNullAnnounceHost() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildAnnounceIpCommand(6379, null));
    }
  }

  @Nested
  @DisplayName("buildAnnouncePortCommand()")
  class BuildAnnouncePortCommand {

    @Test
    @DisplayName("Should build replica-announce-port command")
    void shouldBuildAnnouncePortCommand() {
      final String cmd = RedisCommandBuilder.buildAnnouncePortCommand(6379, 56789);
      assertThat(cmd).contains("replica-announce-port");
      assertThat(cmd).contains("56789");
    }
  }

  @Nested
  @DisplayName("buildSentinelAnnounceIpCommand()")
  class BuildSentinelAnnounceIpCommand {

    @Test
    @DisplayName("Should build sentinel-announce-ip command")
    void shouldBuildSentinelAnnounceIpCommand() {
      final String cmd =
          RedisCommandBuilder.buildSentinelAnnounceIpCommand("host.testcontainers.internal");
      assertThat(cmd).contains("sentinel-announce-ip");
      assertThat(cmd).contains("host.testcontainers.internal");
      assertThat(cmd).contains("26379");
    }

    @Test
    @DisplayName("Should throw NPE for null announceHost")
    void shouldThrowOnNullAnnounceHost() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSentinelAnnounceIpCommand(null));
    }
  }

  @Nested
  @DisplayName("buildSentinelAnnouncePortCommand()")
  class BuildSentinelAnnouncePortCommand {

    @Test
    @DisplayName("Should build sentinel-announce-port command")
    void shouldBuildSentinelAnnouncePortCommand() {
      final String cmd = RedisCommandBuilder.buildSentinelAnnouncePortCommand(36379);
      assertThat(cmd).contains("sentinel-announce-port");
      assertThat(cmd).contains("36379");
      assertThat(cmd).contains("26379");
    }
  }
}
