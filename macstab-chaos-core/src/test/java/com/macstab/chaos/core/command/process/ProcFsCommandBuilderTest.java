/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProcFsCommandBuilder}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ProcFsCommandBuilder")
class ProcFsCommandBuilderTest {

  private ProcessCommandBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new ProcFsCommandBuilder();
  }

  @Nested
  @DisplayName("find process command")
  class FindProcessCommand {

    @Test
    @DisplayName("should search /proc/*/cmdline")
    void shouldSearchProcCmdline() {
      // WHEN
      final String command = builder.buildFindProcessCommand("toxiproxy");

      // THEN
      assertThat(command).contains("/proc/*/cmdline").contains("grep -l 'toxiproxy'");
    }

    @Test
    @DisplayName("should output one PID per line")
    void shouldOutputOnePidPerLine() {
      // WHEN
      final String command = builder.buildFindProcessCommand("toxiproxy");

      // THEN
      assertThat(command).contains("echo $pid");
    }

    @Test
    @DisplayName("should ignore errors")
    void shouldIgnoreErrors() {
      // WHEN
      final String command = builder.buildFindProcessCommand("toxiproxy");

      // THEN
      assertThat(command).contains("2>/dev/null");
    }

    @Test
    @DisplayName("should reject null process name")
    void shouldRejectNullProcessName() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildFindProcessCommand(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("processName must not be null");
    }
  }

  @Nested
  @DisplayName("kill process command")
  class KillProcessCommand {

    @Test
    @DisplayName("should use kill -9")
    void shouldUseKillMinus9() {
      // WHEN
      final String command = builder.buildKillProcessCommand(1234);

      // THEN
      assertThat(command).contains("kill -9 1234");
    }

    @Test
    @DisplayName("should ignore errors with || true")
    void shouldIgnoreErrorsWithOrTrue() {
      // WHEN
      final String command = builder.buildKillProcessCommand(1234);

      // THEN
      assertThat(command).endsWith("|| true");
    }

    @Test
    @DisplayName("should reject zero PID")
    void shouldRejectZeroPid() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildKillProcessCommand(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pid must be positive");
    }

    @Test
    @DisplayName("should reject negative PID")
    void shouldRejectNegativePid() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildKillProcessCommand(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pid must be positive");
    }
  }

  @Nested
  @DisplayName("check process command")
  class CheckProcessCommand {

    @Test
    @DisplayName("should test for /proc/PID directory")
    void shouldTestForProcPidDirectory() {
      // WHEN
      final String command = builder.buildCheckProcessCommand(1234);

      // THEN
      assertThat(command).isEqualTo("test -d /proc/1234");
    }

    @Test
    @DisplayName("should reject invalid PID")
    void shouldRejectInvalidPid() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildCheckProcessCommand(0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("kill all processes command")
  class KillAllProcessesCommand {

    @Test
    @DisplayName("should find and kill all matching processes")
    void shouldFindAndKillAllMatchingProcesses() {
      // WHEN
      final String command = builder.buildKillAllProcessesCommand("toxiproxy");

      // THEN
      assertThat(command)
          .contains("grep -l 'toxiproxy'")
          .contains("kill -9 $pid")
          .contains("sleep 0.2");
    }

    @Test
    @DisplayName("should use for loop")
    void shouldUseForLoop() {
      // WHEN
      final String command = builder.buildKillAllProcessesCommand("toxiproxy");

      // THEN
      assertThat(command).startsWith("for pid in");
    }

    @Test
    @DisplayName("should reject null process name")
    void shouldRejectNullProcessName() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildKillAllProcessesCommand(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("processName must not be null");
    }
  }
}
