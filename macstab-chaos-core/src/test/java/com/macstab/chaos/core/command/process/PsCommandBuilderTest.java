/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.process;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PsCommandBuilder}.
 *
 * <p>Tests portable ps command generation for process management.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("PsCommandBuilder")
class PsCommandBuilderTest {

  private PsCommandBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new PsCommandBuilder();
  }

  @Nested
  @DisplayName("Find Process Command")
  class FindProcessCommandTests {

    @Test
    @DisplayName("should build find command for simple process name")
    void shouldBuildFindCommand_forSimpleProcessName() {
      // Act
      final String command = builder.buildFindProcessCommand("redis-server");

      // Assert
      assertThat(command)
          .contains("ps aux")
          .contains("grep 'redis-server'")
          .contains("grep -v grep")
          .contains("awk '{print $2}'");
    }

    @Test
    @DisplayName("should build find command for process with special chars")
    void shouldBuildFindCommand_forProcessWithSpecialChars() {
      // Act
      final String command = builder.buildFindProcessCommand("node-app.js");

      // Assert
      assertThat(command).contains("grep 'node-app.js'");
    }

    @Test
    @DisplayName("should exclude grep itself from results")
    void shouldExcludeGrep_fromResults() {
      // Act
      final String command = builder.buildFindProcessCommand("toxiproxy");

      // Assert
      assertThat(command).contains("grep -v grep");
    }

    @Test
    @DisplayName("should extract PID column from ps output")
    void shouldExtractPidColumn_fromPsOutput() {
      // Act
      final String command = builder.buildFindProcessCommand("java");

      // Assert
      assertThat(command).contains("awk '{print $2}'");
    }

    @Test
    @DisplayName("should throw NPE when process name is null")
    void shouldThrowNPE_whenProcessNameIsNull() {
      // Act & Assert
      assertThatNullPointerException()
          .isThrownBy(() -> builder.buildFindProcessCommand(null))
          .withMessageContaining("processName must not be null");
    }
  }

  @Nested
  @DisplayName("Kill Process Command")
  class KillProcessCommandTests {

    @Test
    @DisplayName("should build kill command for valid PID")
    void shouldBuildKillCommand_forValidPid() {
      // Act
      final String command = builder.buildKillProcessCommand(1234);

      // Assert
      assertThat(command).contains("kill -9 1234").contains("2>/dev/null").contains("|| true");
    }

    @Test
    @DisplayName("should suppress errors in kill command")
    void shouldSuppressErrors_inKillCommand() {
      // Act
      final String command = builder.buildKillProcessCommand(9999);

      // Assert
      assertThat(command).contains("2>/dev/null").contains("|| true");
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when PID is zero")
    void shouldThrowIAE_whenPidIsZero() {
      // Act & Assert
      assertThatIllegalArgumentException()
          .isThrownBy(() -> builder.buildKillProcessCommand(0))
          .withMessageContaining("pid must be positive");
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when PID is negative")
    void shouldThrowIAE_whenPidIsNegative() {
      // Act & Assert
      assertThatIllegalArgumentException()
          .isThrownBy(() -> builder.buildKillProcessCommand(-1))
          .withMessageContaining("pid must be positive");
    }
  }

  @Nested
  @DisplayName("Check Process Command")
  class CheckProcessCommandTests {

    @Test
    @DisplayName("should build check command for valid PID")
    void shouldBuildCheckCommand_forValidPid() {
      // Act
      final String command = builder.buildCheckProcessCommand(5678);

      // Assert
      assertThat(command).contains("ps -p 5678").contains(">/dev/null").contains("2>&1");
    }

    @Test
    @DisplayName("should suppress output in check command")
    void shouldSuppressOutput_inCheckCommand() {
      // Act
      final String command = builder.buildCheckProcessCommand(1);

      // Assert
      assertThat(command).contains(">/dev/null 2>&1");
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when PID is zero")
    void shouldThrowIAE_whenPidIsZero() {
      // Act & Assert
      assertThatIllegalArgumentException()
          .isThrownBy(() -> builder.buildCheckProcessCommand(0))
          .withMessageContaining("pid must be positive");
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when PID is negative")
    void shouldThrowIAE_whenPidIsNegative() {
      // Act & Assert
      assertThatIllegalArgumentException()
          .isThrownBy(() -> builder.buildCheckProcessCommand(-999))
          .withMessageContaining("pid must be positive");
    }
  }

  @Nested
  @DisplayName("Kill All Processes Command")
  class KillAllProcessesCommandTests {

    @Test
    @DisplayName("should build kill-all command for process name")
    void shouldBuildKillAllCommand_forProcessName() {
      // Act
      final String command = builder.buildKillAllProcessesCommand("toxiproxy");

      // Assert
      assertThat(command)
          .contains("ps aux")
          .contains("grep 'toxiproxy'")
          .contains("grep -v grep")
          .contains("awk '{print $2}'")
          .contains("xargs -r kill -9")
          .contains("2>/dev/null")
          .contains("|| true")
          .contains("sleep 0.2");
    }

    @Test
    @DisplayName("should use xargs -r to handle empty input")
    void shouldUseXargsR_toHandleEmptyInput() {
      // Act
      final String command = builder.buildKillAllProcessesCommand("nginx");

      // Assert
      assertThat(command).contains("xargs -r kill -9");
    }

    @Test
    @DisplayName("should add sleep after kill-all for cleanup")
    void shouldAddSleep_afterKillAllForCleanup() {
      // Act
      final String command = builder.buildKillAllProcessesCommand("redis");

      // Assert
      assertThat(command).endsWith("sleep 0.2");
    }

    @Test
    @DisplayName("should suppress errors in kill-all command")
    void shouldSuppressErrors_inKillAllCommand() {
      // Act
      final String command = builder.buildKillAllProcessesCommand("apache2");

      // Assert
      assertThat(command).contains("2>/dev/null").contains("|| true");
    }

    @Test
    @DisplayName("should throw NPE when process name is null")
    void shouldThrowNPE_whenProcessNameIsNull() {
      // Act & Assert
      assertThatNullPointerException()
          .isThrownBy(() -> builder.buildKillAllProcessesCommand(null))
          .withMessageContaining("processName must not be null");
    }
  }

  @Nested
  @DisplayName("Interface Contract")
  class InterfaceContractTests {

    @Test
    @DisplayName("should implement ProcessCommandBuilder interface")
    void shouldImplementInterface() {
      // Assert
      assertThat(builder).isInstanceOf(ProcessCommandBuilder.class);
    }

    @Test
    @DisplayName("should generate non-empty commands for all methods")
    void shouldGenerateNonEmptyCommands() {
      // Act & Assert
      assertThat(builder.buildFindProcessCommand("test")).isNotEmpty();
      assertThat(builder.buildKillProcessCommand(123)).isNotEmpty();
      assertThat(builder.buildCheckProcessCommand(456)).isNotEmpty();
      assertThat(builder.buildKillAllProcessesCommand("test")).isNotEmpty();
    }

    @Test
    @DisplayName("should return different commands for different inputs")
    void shouldReturnDifferentCommands_forDifferentInputs() {
      // Act
      final String find1 = builder.buildFindProcessCommand("redis");
      final String find2 = builder.buildFindProcessCommand("nginx");
      final String kill1 = builder.buildKillProcessCommand(100);
      final String kill2 = builder.buildKillProcessCommand(200);

      // Assert
      assertThat(find1).isNotEqualTo(find2);
      assertThat(kill1).isNotEqualTo(kill2);
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCasesTests {

    @Test
    @DisplayName("should handle process name with spaces")
    void shouldHandleProcessName_withSpaces() {
      // Act
      final String command = builder.buildFindProcessCommand("java -jar app.jar");

      // Assert
      assertThat(command).contains("grep 'java -jar app.jar'");
    }

    @Test
    @DisplayName("should handle process name with numbers")
    void shouldHandleProcessName_withNumbers() {
      // Act
      final String command = builder.buildFindProcessCommand("redis6379");

      // Assert
      assertThat(command).contains("grep 'redis6379'");
    }

    @Test
    @DisplayName("should handle PID = 1 (init process)")
    void shouldHandlePid1() {
      // Act
      final String command = builder.buildKillProcessCommand(1);

      // Assert
      assertThat(command).contains("kill -9 1");
    }

    @Test
    @DisplayName("should handle large PID values")
    void shouldHandleLargePidValues() {
      // Act
      final String command = builder.buildCheckProcessCommand(999999);

      // Assert
      assertThat(command).contains("ps -p 999999");
    }

    @Test
    @DisplayName("should handle empty process name")
    void shouldHandleEmptyProcessName() {
      // Act
      final String command = builder.buildFindProcessCommand("");

      // Assert
      assertThat(command).contains("grep ''");
    }
  }
}
