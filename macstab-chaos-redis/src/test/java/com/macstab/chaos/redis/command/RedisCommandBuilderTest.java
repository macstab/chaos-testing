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

  // ==================== Core Commands ====================

  @Nested
  @DisplayName("buildRoleCommand()")
  class BuildRoleCommand {

    @Test
    @DisplayName("Should include redis-cli, port and ROLE")
    void shouldBuildRoleCommand() {
      final String cmd = RedisCommandBuilder.buildRoleCommand(6379);
      assertThat(cmd).isEqualTo("redis-cli -p 6379 ROLE");
    }

    @Test
    @DisplayName("Should use given port in command")
    void shouldUseSentinelPort() {
      final String cmd = RedisCommandBuilder.buildRoleCommand(26379);
      assertThat(cmd).isEqualTo("redis-cli -p 26379 ROLE");
    }
  }

  @Nested
  @DisplayName("buildPingCommand()")
  class BuildPingCommand {

    @Test
    @DisplayName("Should include PING and port in command")
    void shouldContainPing() {
      final String cmd = RedisCommandBuilder.buildPingCommand(6379);
      assertThat(cmd).isEqualTo("redis-cli -p 6379 PING");
    }

    @Test
    @DisplayName("Should use custom port")
    void shouldUseCustomPort() {
      final String cmd = RedisCommandBuilder.buildPingCommand(7000);
      assertThat(cmd).contains("-p 7000").contains("PING");
    }
  }

  // ==================== CONFIG Commands ====================

  @Nested
  @DisplayName("buildConfigSet()")
  class BuildConfigSet {

    @Test
    @DisplayName("Should include CONFIG SET with key and value")
    void shouldBuildConfigSet() {
      final String cmd = RedisCommandBuilder.buildConfigSet(6379, "maxmemory", "64mb");
      assertThat(cmd).isEqualTo("redis-cli -p 6379 CONFIG SET maxmemory 64mb");
    }

    @Test
    @DisplayName("Should throw NPE for null key")
    void shouldThrowOnNullKey() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildConfigSet(6379, null, "64mb"))
          .withMessage("key");
    }

    @Test
    @DisplayName("Should throw NPE for null value")
    void shouldThrowOnNullValue() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildConfigSet(6379, "maxmemory", null))
          .withMessage("value");
    }
  }

  // ==================== Sentinel Master Commands ====================

  @Nested
  @DisplayName("buildSentinelMasterCommand(String)")
  class BuildSentinelMasterCommandDeprecated {

    @Test
    @DisplayName("Should delegate to port-param variant using default sentinel port")
    @SuppressWarnings("deprecation")
    void shouldDelegateToPortVariant() {
      final String cmd = RedisCommandBuilder.buildSentinelMasterCommand("mymaster");
      assertThat(cmd)
          .isEqualTo(
              RedisCommandBuilder.buildSentinelMasterCommand(
                  "mymaster", RedisCommandBuilder.DEFAULT_SENTINEL_PORT));
    }

    @Test
    @DisplayName("Should include default sentinel port 26379")
    @SuppressWarnings("deprecation")
    void shouldIncludeDefaultSentinelPort() {
      final String cmd = RedisCommandBuilder.buildSentinelMasterCommand("mymaster");
      assertThat(cmd).contains("26379").contains("SENTINEL master mymaster");
    }

    @Test
    @DisplayName("Should throw NPE for null masterName")
    @SuppressWarnings("deprecation")
    void shouldThrowOnNullMasterName() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSentinelMasterCommand(null))
          .withMessage("masterName");
    }
  }

  @Nested
  @DisplayName("buildSentinelMasterCommand(String, int)")
  class BuildSentinelMasterCommandWithPort {

    @Test
    @DisplayName("Should include SENTINEL master with custom port")
    void shouldIncludeCustomPort() {
      final String cmd = RedisCommandBuilder.buildSentinelMasterCommand("mymaster", 26380);
      assertThat(cmd).isEqualTo("redis-cli -p 26380 SENTINEL master mymaster");
    }

    @Test
    @DisplayName("Should throw NPE for null masterName")
    void shouldThrowOnNullMasterName() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSentinelMasterCommand(null, 26379))
          .withMessage("masterName");
    }
  }

  // ==================== Sentinel Start Commands ====================

  @Nested
  @DisplayName("buildSentinelStartCommand(String, int)")
  class BuildSentinelStartCommandTwoArgs {

    @Test
    @DisplayName("Should include masterIp and quorum using default ports")
    void shouldIncludeMasterIpAndQuorum() {
      final String cmd = RedisCommandBuilder.buildSentinelStartCommand("172.17.0.2", 2);
      assertThat(cmd)
          .contains("172.17.0.2")
          .contains("sentinel monitor mymaster")
          .contains("172.17.0.2 6379 2")
          .contains("redis-server")
          .contains("--sentinel");
    }

    @Test
    @DisplayName("Should use default Redis port 6379 for master")
    void shouldUseDefaultRedisPort() {
      final String cmd = RedisCommandBuilder.buildSentinelStartCommand("10.0.0.1", 1);
      assertThat(cmd).contains("10.0.0.1 6379 1");
    }

    @Test
    @DisplayName("Should use default Sentinel port 26379")
    void shouldUseDefaultSentinelPort() {
      final String cmd = RedisCommandBuilder.buildSentinelStartCommand("10.0.0.1", 1);
      assertThat(cmd).contains("port 26379");
    }

    @Test
    @DisplayName("Should throw NPE for null masterIp")
    void shouldThrowOnNullMasterIp() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSentinelStartCommand(null, 2))
          .withMessage("masterIp");
    }
  }

  @Nested
  @DisplayName("buildSentinelStartCommand(String, int, int, int)")
  class BuildSentinelStartCommandFourArgs {

    @Test
    @DisplayName("Should use custom master port")
    void shouldUseCustomMasterPort() {
      final String cmd = RedisCommandBuilder.buildSentinelStartCommand("10.0.0.1", 6380, 26379, 2);
      assertThat(cmd).contains("10.0.0.1 6380 2");
    }

    @Test
    @DisplayName("Should use custom sentinel port")
    void shouldUseCustomSentinelPort() {
      final String cmd = RedisCommandBuilder.buildSentinelStartCommand("10.0.0.1", 6379, 26380, 2);
      assertThat(cmd).contains("port 26380");
    }

    @Test
    @DisplayName("Should produce valid redis-server sentinel command")
    void shouldProduceValidSentinelCommand() {
      final String cmd =
          RedisCommandBuilder.buildSentinelStartCommand("192.168.1.100", 6379, 26379, 3);
      assertThat(cmd)
          .contains("redis-server /tmp/sentinel.conf --sentinel")
          .contains("sentinel monitor mymaster 192.168.1.100 6379 3")
          .contains("sentinel down-after-milliseconds mymaster")
          .contains("sentinel failover-timeout mymaster");
    }

    @Test
    @DisplayName("Should throw NPE for null masterIp")
    void shouldThrowOnNullMasterIp() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSentinelStartCommand(null, 6379, 26379, 2))
          .withMessage("masterIp");
    }
  }

  // ==================== SLOWLOG Commands ====================

  @Nested
  @DisplayName("buildSlowlogResetCommand()")
  class BuildSlowlogResetCommand {

    @Test
    @DisplayName("Should build SLOWLOG RESET command with port")
    void shouldBuildSlowlogResetCommand() {
      final String cmd = RedisCommandBuilder.buildSlowlogResetCommand(6379);
      assertThat(cmd).isEqualTo("redis-cli -p 6379 SLOWLOG RESET");
    }

    @Test
    @DisplayName("Should use custom port")
    void shouldUseCustomPort() {
      final String cmd = RedisCommandBuilder.buildSlowlogResetCommand(7000);
      assertThat(cmd).contains("-p 7000").contains("SLOWLOG RESET");
    }
  }

  @Nested
  @DisplayName("buildSlowlogGetCommand()")
  class BuildSlowlogGetCommand {

    @Test
    @DisplayName("Should build SLOWLOG GET command with count")
    void shouldBuildSlowlogGetCommand() {
      final String cmd = RedisCommandBuilder.buildSlowlogGetCommand(6379, 128);
      assertThat(cmd).isEqualTo("redis-cli -p 6379 SLOWLOG GET 128");
    }

    @Test
    @DisplayName("Should include count in command")
    void shouldIncludeCount() {
      final String cmd = RedisCommandBuilder.buildSlowlogGetCommand(6379, 50);
      assertThat(cmd).contains("SLOWLOG GET 50");
    }
  }

  // ==================== CLIENT Commands ====================

  @Nested
  @DisplayName("buildClientListCommand()")
  class BuildClientListCommand {

    @Test
    @DisplayName("Should build CLIENT LIST command with port")
    void shouldBuildClientListCommand() {
      final String cmd = RedisCommandBuilder.buildClientListCommand(6379);
      assertThat(cmd).isEqualTo("redis-cli -p 6379 CLIENT LIST");
    }
  }

  // ==================== INFO Commands ====================

  @Nested
  @DisplayName("buildInfoMemoryCommand()")
  class BuildInfoMemoryCommand {

    @Test
    @DisplayName("Should build INFO memory command")
    void shouldBuildInfoMemoryCommand() {
      final String cmd = RedisCommandBuilder.buildInfoMemoryCommand(6379);
      assertThat(cmd).isEqualTo("redis-cli -p 6379 INFO memory");
    }
  }

  @Nested
  @DisplayName("buildInfoCommand()")
  class BuildInfoCommand {

    @Test
    @DisplayName("Should build INFO section command")
    void shouldBuildInfoSectionCommand() {
      final String cmd = RedisCommandBuilder.buildInfoCommand(6379, "replication");
      assertThat(cmd).isEqualTo("redis-cli -p 6379 INFO replication");
    }

    @Test
    @DisplayName("Should accept any section name")
    void shouldAcceptAnySectionName() {
      final String cmd = RedisCommandBuilder.buildInfoCommand(6379, "server");
      assertThat(cmd).contains("INFO server");
    }

    @Test
    @DisplayName("Should throw NPE for null section")
    void shouldThrowOnNullSection() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildInfoCommand(6379, null))
          .withMessage("section");
    }
  }

  // ==================== Key Commands ====================

  @Nested
  @DisplayName("buildSetCommand()")
  class BuildSetCommand {

    @Test
    @DisplayName("Should build SET command with key and value")
    void shouldBuildSetCommand() {
      final String cmd = RedisCommandBuilder.buildSetCommand(6379, "mykey", "myvalue");
      assertThat(cmd).isEqualTo("redis-cli -p 6379 SET mykey myvalue");
    }

    @Test
    @DisplayName("Should throw NPE for null key")
    void shouldThrowOnNullKey() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSetCommand(6379, null, "value"))
          .withMessage("key");
    }

    @Test
    @DisplayName("Should throw NPE for null value")
    void shouldThrowOnNullValue() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSetCommand(6379, "key", null))
          .withMessage("value");
    }
  }

  @Nested
  @DisplayName("buildGetCommand()")
  class BuildGetCommand {

    @Test
    @DisplayName("Should build GET command with key")
    void shouldBuildGetCommand() {
      final String cmd = RedisCommandBuilder.buildGetCommand(6379, "mykey");
      assertThat(cmd).isEqualTo("redis-cli -p 6379 GET mykey");
    }

    @Test
    @DisplayName("Should throw NPE for null key")
    void shouldThrowOnNullKey() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildGetCommand(6379, null))
          .withMessage("key");
    }
  }

  @Nested
  @DisplayName("buildDelCommand()")
  class BuildDelCommand {

    @Test
    @DisplayName("Should build DEL command with key")
    void shouldBuildDelCommand() {
      final String cmd = RedisCommandBuilder.buildDelCommand(6379, "mykey");
      assertThat(cmd).isEqualTo("redis-cli -p 6379 DEL mykey");
    }

    @Test
    @DisplayName("Should throw NPE for null key")
    void shouldThrowOnNullKey() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildDelCommand(6379, null))
          .withMessage("key");
    }
  }

  // ==================== Announce / Replication Commands ====================

  @Nested
  @DisplayName("buildAnnounceIpCommand()")
  class BuildAnnounceIpCommand {

    @Test
    @DisplayName("Should build replica-announce-ip command")
    void shouldBuildAnnounceIpCommand() {
      final String cmd =
          RedisCommandBuilder.buildAnnounceIpCommand(6379, "host.testcontainers.internal");
      assertThat(cmd)
          .contains("replica-announce-ip")
          .contains("host.testcontainers.internal")
          .contains("6379");
    }

    @Test
    @DisplayName("Should throw NPE for null announceHost")
    void shouldThrowOnNullAnnounceHost() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildAnnounceIpCommand(6379, null))
          .withMessage("announceHost");
    }
  }

  @Nested
  @DisplayName("buildAnnouncePortCommand()")
  class BuildAnnouncePortCommand {

    @Test
    @DisplayName("Should build replica-announce-port command with port")
    void shouldBuildAnnouncePortCommand() {
      final String cmd = RedisCommandBuilder.buildAnnouncePortCommand(6379, 56789);
      assertThat(cmd).contains("replica-announce-port").contains("56789");
    }
  }

  // ==================== Sentinel Announce Commands ====================

  @Nested
  @DisplayName("buildSentinelAnnounceIpCommand(int, String)")
  class BuildSentinelAnnounceIpCommandWithPort {

    @Test
    @DisplayName("Should build sentinel-announce-ip command with custom port")
    void shouldBuildWithCustomPort() {
      final String cmd =
          RedisCommandBuilder.buildSentinelAnnounceIpCommand(26380, "host.testcontainers.internal");
      assertThat(cmd)
          .contains("26380")
          .contains("sentinel-announce-ip")
          .contains("host.testcontainers.internal");
    }

    @Test
    @DisplayName("Should throw NPE for null announceHost")
    void shouldThrowOnNullAnnounceHost() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSentinelAnnounceIpCommand(26379, null))
          .withMessage("announceHost");
    }
  }

  @Nested
  @DisplayName("buildSentinelAnnounceIpCommand(String) — deprecated")
  class BuildSentinelAnnounceIpCommandDeprecated {

    @Test
    @DisplayName("Should delegate to port-param variant using default sentinel port")
    @SuppressWarnings("deprecation")
    void shouldDelegateToPortVariant() {
      final String cmd =
          RedisCommandBuilder.buildSentinelAnnounceIpCommand("host.testcontainers.internal");
      assertThat(cmd)
          .isEqualTo(
              RedisCommandBuilder.buildSentinelAnnounceIpCommand(
                  RedisCommandBuilder.DEFAULT_SENTINEL_PORT, "host.testcontainers.internal"));
    }

    @Test
    @DisplayName("Should include default sentinel port 26379")
    @SuppressWarnings("deprecation")
    void shouldIncludeDefaultSentinelPort() {
      final String cmd =
          RedisCommandBuilder.buildSentinelAnnounceIpCommand("host.testcontainers.internal");
      assertThat(cmd).contains("26379").contains("sentinel-announce-ip");
    }

    @Test
    @DisplayName("Should throw NPE for null announceHost")
    @SuppressWarnings("deprecation")
    void shouldThrowOnNullAnnounceHost() {
      assertThatNullPointerException()
          .isThrownBy(() -> RedisCommandBuilder.buildSentinelAnnounceIpCommand((String) null))
          .withMessage("announceHost");
    }
  }

  @Nested
  @DisplayName("buildSentinelAnnouncePortCommand(int, int)")
  class BuildSentinelAnnouncePortCommandWithPort {

    @Test
    @DisplayName("Should build sentinel-announce-port command with custom sentinel port")
    void shouldBuildWithCustomPort() {
      final String cmd = RedisCommandBuilder.buildSentinelAnnouncePortCommand(26380, 36379);
      assertThat(cmd).contains("26380").contains("sentinel-announce-port").contains("36379");
    }
  }

  @Nested
  @DisplayName("buildSentinelAnnouncePortCommand(int) — deprecated")
  class BuildSentinelAnnouncePortCommandDeprecated {

    @Test
    @DisplayName("Should delegate to port-param variant using default sentinel port")
    @SuppressWarnings("deprecation")
    void shouldDelegateToPortVariant() {
      final String cmd = RedisCommandBuilder.buildSentinelAnnouncePortCommand(36379);
      assertThat(cmd)
          .isEqualTo(
              RedisCommandBuilder.buildSentinelAnnouncePortCommand(
                  RedisCommandBuilder.DEFAULT_SENTINEL_PORT, 36379));
    }

    @Test
    @DisplayName("Should include default sentinel port 26379 and announce port")
    @SuppressWarnings("deprecation")
    void shouldIncludeDefaultSentinelPort() {
      final String cmd = RedisCommandBuilder.buildSentinelAnnouncePortCommand(36379);
      assertThat(cmd).contains("26379").contains("sentinel-announce-port").contains("36379");
    }
  }
}
