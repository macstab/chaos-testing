/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CommandWithArgs}. */
@DisplayName("CommandWithArgs")
class CommandWithArgsTest {

  @Nested
  @DisplayName("parse()")
  class ParseTests {

    @Test
    @DisplayName("Should parse GET command with key")
    void shouldParseGetCommand() {
      // ARRANGE
      final String line = "1234.0 [0 127.0.0.1:1234] \"GET\" \"user:123\"";

      // ACT
      final CommandWithArgs cmd = CommandWithArgs.parse(line);

      // ASSERT
      assertThat(cmd).isNotNull();
      assertThat(cmd.getCommand()).isEqualTo("GET");
      assertThat(cmd.getKey()).isEqualTo("user:123");
      assertThat(cmd.getValue()).isNull();
    }

    @Test
    @DisplayName("Should parse SET command with key and value")
    void shouldParseSetCommand() {
      final String line = "1234.0 [0 127.0.0.1:1234] \"SET\" \"user:456\" \"john\"";
      final CommandWithArgs cmd = CommandWithArgs.parse(line);

      assertThat(cmd).isNotNull();
      assertThat(cmd.getCommand()).isEqualTo("SET");
      assertThat(cmd.getKey()).isEqualTo("user:456");
      assertThat(cmd.getValue()).isEqualTo("john");
    }

    @Test
    @DisplayName("Should parse SET with TTL arguments")
    void shouldParseSetWithExpiration() {
      final String line = "1234.0 [0 127.0.0.1:1234] \"SET\" \"key\" \"val\" \"EX\" \"3600\"";
      final CommandWithArgs cmd = CommandWithArgs.parse(line);

      assertThat(cmd).isNotNull();
      assertThat(cmd.getArgs()).containsExactly("key", "val", "EX", "3600");
    }

    @Test
    @DisplayName("Should return null for line with no quotes")
    void shouldReturnNullForMalformed() {
      assertThat(CommandWithArgs.parse("no quotes here")).isNull();
    }

    @Test
    @DisplayName("Should return null for null input")
    void shouldReturnNullForNull() {
      assertThat(CommandWithArgs.parse(null)).isNull();
    }

    @Test
    @DisplayName("Should return null for empty string")
    void shouldReturnNullForEmpty() {
      assertThat(CommandWithArgs.parse("")).isNull();
    }
  }

  @Nested
  @DisplayName("Getters")
  class Getters {

    @Test
    @DisplayName("getKey() should return null when no args")
    void getKeyShouldReturnNullForNoArgs() {
      // A line with only command, no args
      final String line = "1234.0 [0 127.0.0.1:1234] \"PING\"";
      final CommandWithArgs cmd = CommandWithArgs.parse(line);
      assertThat(cmd).isNotNull();
      assertThat(cmd.getKey()).isNull();
      assertThat(cmd.getValue()).isNull();
    }

    @Test
    @DisplayName("getArgs() should return immutable list")
    void argsShouldBeImmutable() {
      final String line = "1234.0 [0 127.0.0.1:1234] \"GET\" \"k\"";
      final CommandWithArgs cmd = CommandWithArgs.parse(line);
      assertThat(cmd).isNotNull();
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> cmd.getArgs().add("x"))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("toString()")
  class ToStringTests {

    @Test
    @DisplayName("Should format as 'COMMAND arg1 arg2'")
    void shouldFormatCorrectly() {
      final String line = "1234.0 [0 127.0.0.1:1234] \"SET\" \"k\" \"v\"";
      final CommandWithArgs cmd = CommandWithArgs.parse(line);
      assertThat(cmd).isNotNull();
      assertThat(cmd.toString()).isEqualTo("SET k v");
    }
  }
}
