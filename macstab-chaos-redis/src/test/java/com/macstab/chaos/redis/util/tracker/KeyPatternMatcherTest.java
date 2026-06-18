/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link KeyPatternMatcher}. */
@DisplayName("KeyPatternMatcher")
class KeyPatternMatcherTest {

  private static final String GET_USER = "1234.567 [0 127.0.0.1:12345] \"GET\" \"user:123\"";
  private static final String GET_SESSION = "1234.568 [0 127.0.0.1:12345] \"GET\" \"session:abc\"";
  private static final String SET_USER =
      "1234.569 [0 127.0.0.1:12345] \"SET\" \"user:456\" \"val\"";
  private static final String GET_PRODUCT = "1234.570 [0 127.0.0.1:12345] \"GET\" \"product:789\"";

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw for null capturedCommands")
    void shouldThrowForNull() {
      assertThatThrownBy(() -> new KeyPatternMatcher(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("countCommandsMatchingKeyPattern()")
  class CountCommands {

    @Test
    @DisplayName("Should count GET commands matching user:* pattern")
    void shouldCountMatchingCommands() {
      // ARRANGE
      final KeyPatternMatcher matcher =
          new KeyPatternMatcher(List.of(GET_USER, GET_SESSION, SET_USER));

      // ACT & ASSERT
      assertThat(matcher.countCommandsMatchingKeyPattern("GET", "user:*")).isEqualTo(1);
      assertThat(matcher.countCommandsMatchingKeyPattern("GET", "session:*")).isEqualTo(1);
      assertThat(matcher.countCommandsMatchingKeyPattern("GET", "product:*")).isEqualTo(0);
    }

    @Test
    @DisplayName("Should match SET commands")
    void shouldMatchSetCommands() {
      final KeyPatternMatcher matcher = new KeyPatternMatcher(List.of(SET_USER));
      assertThat(matcher.countCommandsMatchingKeyPattern("SET", "user:*")).isEqualTo(1);
      assertThat(matcher.countCommandsMatchingKeyPattern("GET", "user:*")).isEqualTo(0);
    }

    @Test
    @DisplayName("Should match wildcard *")
    void shouldMatchWildcard() {
      final KeyPatternMatcher matcher =
          new KeyPatternMatcher(List.of(GET_USER, GET_SESSION, GET_PRODUCT));
      assertThat(matcher.countCommandsMatchingKeyPattern("GET", "*")).isEqualTo(3);
    }

    @Test
    @DisplayName("Should throw for null command")
    void shouldThrowForNullCommand() {
      final KeyPatternMatcher matcher = new KeyPatternMatcher(List.of(GET_USER));
      assertThatThrownBy(() -> matcher.countCommandsMatchingKeyPattern(null, "user:*"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw for null keyPattern")
    void shouldThrowForNullPattern() {
      final KeyPatternMatcher matcher = new KeyPatternMatcher(List.of(GET_USER));
      assertThatThrownBy(() -> matcher.countCommandsMatchingKeyPattern("GET", null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("getCommandsMatchingKeyPattern()")
  class GetCommands {

    @Test
    @DisplayName("Should return matching lines")
    void shouldReturnMatchingLines() {
      final KeyPatternMatcher matcher =
          new KeyPatternMatcher(List.of(GET_USER, GET_SESSION, GET_PRODUCT));
      final List<String> result = matcher.getCommandsMatchingKeyPattern("user:*");
      assertThat(result).containsExactly(GET_USER);
    }

    @Test
    @DisplayName("Should return empty for no matches")
    void shouldReturnEmptyForNoMatches() {
      final KeyPatternMatcher matcher = new KeyPatternMatcher(List.of(GET_USER, GET_SESSION));
      assertThat(matcher.getCommandsMatchingKeyPattern("product:*")).isEmpty();
    }

    @Test
    @DisplayName("Should support single character wildcard ?")
    void shouldSupportSingleCharWildcard() {
      final String line = "1234.0 [0 127.0.0.1:1234] \"GET\" \"key:a\"";
      final String line2 = "1234.1 [0 127.0.0.1:1234] \"GET\" \"key:ab\"";
      final KeyPatternMatcher matcher = new KeyPatternMatcher(List.of(line, line2));
      // "key:?" should match "key:a" but not "key:ab"
      assertThat(matcher.getCommandsMatchingKeyPattern("key:?")).containsExactly(line);
    }
  }

  @Nested
  @DisplayName("convertGlobToRegex()")
  class GlobToRegex {

    @Test
    @DisplayName("Should escape dots in pattern")
    void shouldEscapeDots() {
      final java.util.regex.Pattern p = KeyPatternMatcher.convertGlobToRegex("a.b");
      assertThat(p.matcher("a.b").matches()).isTrue();
      assertThat(p.matcher("axb").matches()).isFalse();
    }

    @Test
    @DisplayName("Should convert * to .* ")
    void shouldConvertStar() {
      final java.util.regex.Pattern p = KeyPatternMatcher.convertGlobToRegex("user:*");
      assertThat(p.matcher("user:123").matches()).isTrue();
      assertThat(p.matcher("user:").matches()).isTrue();
      assertThat(p.matcher("session:123").matches()).isFalse();
    }
  }
}
