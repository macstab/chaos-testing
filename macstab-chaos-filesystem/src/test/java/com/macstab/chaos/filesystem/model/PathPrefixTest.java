/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PathPrefix (unit)")
class PathPrefixTest {

  @Nested
  @DisplayName("AbsolutePath")
  class AbsolutePathCases {

    @Test
    @DisplayName("accepts a simple absolute path")
    void acceptsSimple() {
      assertThat(PathPrefix.path("/var/log").toSelector()).isEqualTo("/var/log");
    }

    @Test
    @DisplayName("accepts paths with safe special chars")
    void acceptsSafeSpecials() {
      assertThat(PathPrefix.path("/data/wal.log-1").toSelector()).isEqualTo("/data/wal.log-1");
    }

    @Test
    @DisplayName("rejects null")
    void rejectsNull() {
      assertThatThrownBy(() -> PathPrefix.path(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects blank")
    void rejectsBlank() {
      assertThatThrownBy(() -> PathPrefix.path("  "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("rejects relative path")
    void rejectsRelative() {
      assertThatThrownBy(() -> PathPrefix.path("var/log"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("absolute");
    }

    @Test
    @DisplayName("rejects newline (config-file injection guard)")
    void rejectsNewline() {
      assertThatThrownBy(() -> PathPrefix.path("/data\nmalicious"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("newline");
      assertThatThrownBy(() -> PathPrefix.path("/data\rmalicious"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("newline");
    }

    @Test
    @DisplayName("rejects ':' (field-separator collision)")
    void rejectsColon() {
      assertThatThrownBy(() -> PathPrefix.path("/data:foo"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(":");
    }

    @Test
    @DisplayName("rejects '..' as a path segment")
    void rejectsTraversalSegment() {
      assertThatThrownBy(() -> PathPrefix.path("/data/../etc"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("..");
      assertThatThrownBy(() -> PathPrefix.path("/..")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> PathPrefix.path("/../"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("'..' inside a segment is NOT traversal — accepted")
    void acceptsDotsInsideSegment() {
      assertThat(PathPrefix.path("/data/foo..bar").toSelector()).isEqualTo("/data/foo..bar");
      assertThat(PathPrefix.path("/data/foo../bar").toSelector()).isEqualTo("/data/foo../bar");
    }

    @Test
    @DisplayName("rejects paths longer than MAX_PATH_LENGTH")
    void rejectsTooLong() {
      final String tooLong = "/" + "a".repeat(PathPrefix.MAX_PATH_LENGTH);
      assertThatThrownBy(() -> PathPrefix.path(tooLong))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(String.valueOf(PathPrefix.MAX_PATH_LENGTH));
    }

    @Test
    @DisplayName("accepts paths at exactly MAX_PATH_LENGTH")
    void acceptsAtBoundary() {
      final String boundary = "/" + "a".repeat(PathPrefix.MAX_PATH_LENGTH - 1);
      assertThat(boundary.length()).isEqualTo(PathPrefix.MAX_PATH_LENGTH);
      assertThat(PathPrefix.path(boundary).toSelector()).isEqualTo(boundary);
    }
  }

  @Nested
  @DisplayName("Wildcard")
  class WildcardCases {

    @Test
    @DisplayName("single instance available via factory")
    void singletonViaFactory() {
      assertThat(PathPrefix.wildcard()).isSameAs(PathPrefix.Wildcard.ANY);
    }

    @Test
    @DisplayName("renders as '*'")
    void rendersAsStar() {
      assertThat(PathPrefix.wildcard().toSelector()).isEqualTo("*");
    }
  }
}
