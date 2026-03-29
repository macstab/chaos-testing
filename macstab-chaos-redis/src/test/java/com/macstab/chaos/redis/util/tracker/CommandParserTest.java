/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CommandParser}. */
@DisplayName("CommandParser")
class CommandParserTest {

  private static final String SET_LINE_1 = "1234.0 [0 127.0.0.1:1234] \"SET\" \"key1\" \"value1\"";
  private static final String SET_LINE_2 = "1234.1 [0 127.0.0.1:1234] \"SET\" \"key2\" \"value2\"";
  private static final String GET_LINE = "1234.2 [0 127.0.0.1:1234] \"GET\" \"key1\"";

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw for null capturedCommands")
    void shouldThrowForNull() {
      assertThatThrownBy(() -> new CommandParser(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("getCommandsWithArguments()")
  class GetCommands {

    @Test
    @DisplayName("Should return SET commands with parsed args")
    void shouldReturnSetCommands() {
      // ARRANGE
      final CommandParser parser = new CommandParser(List.of(SET_LINE_1, SET_LINE_2, GET_LINE));

      // ACT
      final List<CommandWithArgs> sets = parser.getCommandsWithArguments("SET");

      // ASSERT
      assertThat(sets).hasSize(2);
      assertThat(sets.get(0).getKey()).isEqualTo("key1");
      assertThat(sets.get(0).getValue()).isEqualTo("value1");
      assertThat(sets.get(1).getKey()).isEqualTo("key2");
    }

    @Test
    @DisplayName("Should return empty for unknown command")
    void shouldReturnEmptyForUnknownCommand() {
      final CommandParser parser = new CommandParser(List.of(SET_LINE_1, GET_LINE));
      assertThat(parser.getCommandsWithArguments("HGET")).isEmpty();
    }

    @Test
    @DisplayName("Should be case-insensitive for command name")
    void shouldBeCaseInsensitive() {
      final CommandParser parser = new CommandParser(List.of(SET_LINE_1));
      assertThat(parser.getCommandsWithArguments("set")).hasSize(1);
      assertThat(parser.getCommandsWithArguments("SET")).hasSize(1);
      assertThat(parser.getCommandsWithArguments("Set")).hasSize(1);
    }

    @Test
    @DisplayName("Should throw for null command")
    void shouldThrowForNullCommand() {
      final CommandParser parser = new CommandParser(List.of());
      assertThatThrownBy(() -> parser.getCommandsWithArguments(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle empty command list")
    void shouldHandleEmpty() {
      final CommandParser parser = new CommandParser(List.of());
      assertThat(parser.getCommandsWithArguments("GET")).isEmpty();
    }
  }
}
