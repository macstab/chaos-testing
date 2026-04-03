/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ShellCapability} and shell capability queries.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ShellCapability")
class ShellCapabilityTest {

  @Nested
  @DisplayName("BashShell capabilities")
  class BashCapabilities {

    private final Shell bash = new BashShell();

    @Test
    @DisplayName("supports all capabilities")
    void supportsAll() {
      for (final ShellCapability cap : ShellCapability.values()) {
        assertThat(bash.supports(cap))
            .as("Bash should support %s", cap)
            .isTrue();
      }
    }
  }

  @Nested
  @DisplayName("AshShell capabilities")
  class AshCapabilities {

    private final Shell ash = new AshShell();

    @Test
    @DisplayName("supports COMMAND_SUBSTITUTION")
    void supportsCommandSubstitution() {
      assertThat(ash.supports(ShellCapability.COMMAND_SUBSTITUTION)).isTrue();
    }

    @Test
    @DisplayName("does not support DEV_TCP")
    void doesNotSupportDevTcp() {
      assertThat(ash.supports(ShellCapability.DEV_TCP)).isFalse();
    }

    @Test
    @DisplayName("does not support PROCESS_SUBSTITUTION")
    void doesNotSupportProcessSubstitution() {
      assertThat(ash.supports(ShellCapability.PROCESS_SUBSTITUTION)).isFalse();
    }

    @Test
    @DisplayName("does not support BRACE_EXPANSION")
    void doesNotSupportBraceExpansion() {
      assertThat(ash.supports(ShellCapability.BRACE_EXPANSION)).isFalse();
    }

    @Test
    @DisplayName("does not support EXTENDED_TEST")
    void doesNotSupportExtendedTest() {
      assertThat(ash.supports(ShellCapability.EXTENDED_TEST)).isFalse();
    }

    @Test
    @DisplayName("does not support ARRAYS")
    void doesNotSupportArrays() {
      assertThat(ash.supports(ShellCapability.ARRAYS)).isFalse();
    }

    @Test
    @DisplayName("does not support ASSOCIATIVE_ARRAYS")
    void doesNotSupportAssociativeArrays() {
      assertThat(ash.supports(ShellCapability.ASSOCIATIVE_ARRAYS)).isFalse();
    }
  }

  @Nested
  @DisplayName("AshShell properties")
  class AshProperties {

    private final AshShell ash = new AshShell();

    @Test
    @DisplayName("type is ASH")
    void typeIsAsh() {
      assertThat(ash.getType()).isEqualTo(ShellType.ASH);
    }

    @Test
    @DisplayName("binary is /bin/sh")
    void binaryIsBinSh() {
      assertThat(ash.getBinary()).isEqualTo("/bin/sh");
    }

    @SuppressWarnings("removal")
    @Test
    @DisplayName("supportsDevTcp returns false")
    void devTcpFalse() {
      assertThat(ash.supportsDevTcp()).isFalse();
    }
  }
}
