/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IptablesCommandBuilder}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("IptablesCommandBuilder")
class IptablesCommandBuilderTest {

  private NetworkCommandBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new IptablesCommandBuilder();
  }

  @Nested
  @DisplayName("add redirect command")
  class AddRedirectCommand {

    @Test
    @DisplayName("should build dual-chain redirect (PREROUTING + OUTPUT)")
    void shouldBuildDualChainRedirect() {
      // WHEN
      final String command = builder.buildAddRedirectCommand(6379, 16379);

      // THEN
      assertThat(command)
          .contains("iptables -t nat -A PREROUTING")
          .contains("iptables -t nat -A OUTPUT")
          .contains("--dport 6379")
          .contains("--to-port 16379")
          .contains("&&");
    }

    @Test
    @DisplayName("should include TCP protocol")
    void shouldIncludeTcpProtocol() {
      // WHEN
      final String command = builder.buildAddRedirectCommand(6379, 16379);

      // THEN
      assertThat(command).contains("-p tcp");
    }

    @Test
    @DisplayName("should redirect stderr to stdout")
    void shouldRedirectStderrToStdout() {
      // WHEN
      final String command = builder.buildAddRedirectCommand(6379, 16379);

      // THEN
      assertThat(command).contains("2>&1");
    }

    @Test
    @DisplayName("should reject invalid from port")
    void shouldRejectInvalidFromPort() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildAddRedirectCommand(0, 16379))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("fromPort")
          .hasMessageContaining("[1, 65535]");
    }

    @Test
    @DisplayName("should reject invalid to port")
    void shouldRejectInvalidToPort() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildAddRedirectCommand(6379, 70000))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toPort")
          .hasMessageContaining("[1, 65535]");
    }

    @Test
    @DisplayName("should accept port 1")
    void shouldAcceptPort1() {
      // WHEN
      final String command = builder.buildAddRedirectCommand(1, 2);

      // THEN
      assertThat(command).contains("--dport 1").contains("--to-port 2");
    }

    @Test
    @DisplayName("should accept port 65535")
    void shouldAcceptPort65535() {
      // WHEN
      final String command = builder.buildAddRedirectCommand(65535, 65534);

      // THEN
      assertThat(command).contains("--dport 65535").contains("--to-port 65534");
    }
  }

  @Nested
  @DisplayName("remove redirect command")
  class RemoveRedirectCommand {

    @Test
    @DisplayName("should build delete command")
    void shouldBuildDeleteCommand() {
      // WHEN
      final String command = builder.buildRemoveRedirectCommand(6379, 16379);

      // THEN
      assertThat(command)
          .contains("iptables -t nat -D PREROUTING")
          .contains("--dport 6379")
          .contains("--to-port 16379");
    }

    @Test
    @DisplayName("should ignore errors with || true")
    void shouldIgnoreErrorsWithOrTrue() {
      // WHEN
      final String command = builder.buildRemoveRedirectCommand(6379, 16379);

      // THEN
      assertThat(command).endsWith("|| true");
    }

    @Test
    @DisplayName("should reject invalid ports")
    void shouldRejectInvalidPorts() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildRemoveRedirectCommand(-1, 16379))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("clear redirects command")
  class ClearRedirectsCommand {

    @Test
    @DisplayName("should flush both PREROUTING and OUTPUT chains")
    void shouldFlushBothPreroutingAndOutputChains() {
      // WHEN
      final String command = builder.buildClearRedirectsCommand();

      // THEN
      assertThat(command)
          .contains("iptables -t nat -F PREROUTING")
          .contains("iptables -t nat -F OUTPUT")
          .contains("&&");
    }
  }

  @Nested
  @DisplayName("port check command")
  class PortCheckCommand {

    @Test
    @DisplayName("should use curl for port check")
    void shouldUseCurlForPortCheck() {
      // WHEN
      final String command = builder.buildPortCheckCommand(6379);

      // THEN
      assertThat(command).contains("curl").contains("localhost:6379");
    }

    @Test
    @DisplayName("should accept exit codes 0 or 52")
    void shouldAcceptExitCodes0Or52() {
      // WHEN
      final String command = builder.buildPortCheckCommand(6379);

      // THEN
      assertThat(command).contains("test $? -eq 0 -o $? -eq 52");
    }

    @Test
    @DisplayName("should set connection timeout")
    void shouldSetConnectionTimeout() {
      // WHEN
      final String command = builder.buildPortCheckCommand(6379);

      // THEN
      assertThat(command).contains("--connect-timeout 1").contains("--max-time 1");
    }

    @Test
    @DisplayName("should reject invalid port")
    void shouldRejectInvalidPort() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildPortCheckCommand(100000))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("port");
    }
  }
}
