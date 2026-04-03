/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * Unit tests for {@link ShellSanitizer}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ShellSanitizer")
class ShellSanitizerTest {

  @Nested
  @DisplayName("valid arguments")
  class ValidArguments {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "stress-ng",
          "cpulimit",
          "util-linux",
          "iproute2",
          "ca-certificates",
          "python3",
          "redis-server",
          "my.package.name",
          "tool_v2",
          "a"
        })
    @DisplayName("accepts safe argument and returns it unchanged")
    void acceptsSafeArguments(final String value) {
      assertThat(ShellSanitizer.validateArgument(value, "tool")).isEqualTo(value);
    }
  }

  @Nested
  @DisplayName("rejected arguments")
  class RejectedArguments {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "stress'ng", // single quote — shell escape
          "tool\"name", // double quote
          "tool`cmd`", // backtick — command substitution
          "tool$var", // dollar — variable expansion
          "tool;rm", // semicolon — command chaining
          "tool|grep", // pipe
          "tool&bg", // ampersand — background
          "tool>out", // output redirect
          "tool<in", // input redirect
          "tool(x)", // subshell
          "tool\\n", // backslash
          "tool name", // space
          "tool\nnewline" // newline
        })
    @DisplayName("rejects unsafe argument")
    void rejectsUnsafeArgument(final String value) {
      assertThatThrownBy(() -> ShellSanitizer.validateArgument(value, "tool"))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("unsafe characters");
    }

    @Test
    @DisplayName("rejects blank argument")
    void rejectsBlank() {
      assertThatThrownBy(() -> ShellSanitizer.validateArgument("  ", "tool"))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("rejects empty argument")
    void rejectsEmpty() {
      assertThatThrownBy(() -> ShellSanitizer.validateArgument("", "tool"))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("must not be blank");
    }
  }

  @Nested
  @DisplayName("null handling")
  class NullHandling {

    @Test
    @DisplayName("throws NullPointerException for null value")
    void nullValue() {
      assertThatThrownBy(() -> ShellSanitizer.validateArgument(null, "tool"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("throws NullPointerException for null paramName")
    void nullParamName() {
      assertThatThrownBy(() -> ShellSanitizer.validateArgument("stress-ng", null))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
