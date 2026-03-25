/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContainerIdFormatter}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ContainerIdFormatter")
class ContainerIdFormatterTest {

  private static final String FULL_ID =
      "06c27a7ed60269392f23dac224bc3eb7f5be70abcc77b25fd10a004bacf099bb";

  @Nested
  @DisplayName("truncate with default length")
  class TruncateDefaultLength {

    @Test
    @DisplayName("should truncate full ID to 12 characters")
    void shouldTruncateFullIdTo12Characters() {
      // WHEN
      final String truncated = ContainerIdFormatter.truncate(FULL_ID);

      // THEN
      assertThat(truncated).isEqualTo("06c27a7ed602").hasSize(12);
    }

    @Test
    @DisplayName("should return short ID unchanged")
    void shouldReturnShortIdUnchanged() {
      // WHEN
      final String truncated = ContainerIdFormatter.truncate("abc123");

      // THEN
      assertThat(truncated).isEqualTo("abc123");
    }

    @Test
    @DisplayName("should handle null ID")
    void shouldHandleNullId() {
      // WHEN
      final String truncated = ContainerIdFormatter.truncate(null);

      // THEN
      assertThat(truncated).isNull();
    }

    @Test
    @DisplayName("should handle empty ID")
    void shouldHandleEmptyId() {
      // WHEN
      final String truncated = ContainerIdFormatter.truncate("");

      // THEN
      assertThat(truncated).isEmpty();
    }

    @Test
    @DisplayName("should handle ID exactly 12 characters")
    void shouldHandleIdExactly12Characters() {
      // WHEN
      final String truncated = ContainerIdFormatter.truncate("06c27a7ed602");

      // THEN
      assertThat(truncated).isEqualTo("06c27a7ed602");
    }
  }

  @Nested
  @DisplayName("truncate with custom length")
  class TruncateCustomLength {

    @Test
    @DisplayName("should truncate to 8 characters")
    void shouldTruncateTo8Characters() {
      // WHEN
      final String truncated = ContainerIdFormatter.truncate(FULL_ID, 8);

      // THEN
      assertThat(truncated).isEqualTo("06c27a7e").hasSize(8);
    }

    @Test
    @DisplayName("should truncate to 16 characters")
    void shouldTruncateTo16Characters() {
      // WHEN
      final String truncated = ContainerIdFormatter.truncate(FULL_ID, 16);

      // THEN
      assertThat(truncated).isEqualTo("06c27a7ed6026939").hasSize(16);
    }

    @Test
    @DisplayName("should reject zero length")
    void shouldRejectZeroLength() {
      // WHEN / THEN
      assertThatThrownBy(() -> ContainerIdFormatter.truncate(FULL_ID, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Truncation length must be positive");
    }

    @Test
    @DisplayName("should reject negative length")
    void shouldRejectNegativeLength() {
      // WHEN / THEN
      assertThatThrownBy(() -> ContainerIdFormatter.truncate(FULL_ID, -1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Truncation length must be positive");
    }

    @Test
    @DisplayName("should handle length longer than ID")
    void shouldHandleLengthLongerThanId() {
      // WHEN
      final String truncated = ContainerIdFormatter.truncate("abc123", 20);

      // THEN
      assertThat(truncated).isEqualTo("abc123");
    }
  }

  @Nested
  @DisplayName("isTruncated")
  class IsTruncated {

    @Test
    @DisplayName("should detect truncated ID (12 chars)")
    void shouldDetectTruncatedId12Chars() {
      // WHEN
      final boolean truncated = ContainerIdFormatter.isTruncated("06c27a7ed602");

      // THEN
      assertThat(truncated).isTrue();
    }

    @Test
    @DisplayName("should detect full ID (64 chars)")
    void shouldDetectFullId64Chars() {
      // WHEN
      final boolean truncated = ContainerIdFormatter.isTruncated(FULL_ID);

      // THEN
      assertThat(truncated).isFalse();
    }

    @Test
    @DisplayName("should handle null as not truncated")
    void shouldHandleNullAsNotTruncated() {
      // WHEN
      final boolean truncated = ContainerIdFormatter.isTruncated(null);

      // THEN
      assertThat(truncated).isFalse();
    }

    @Test
    @DisplayName("should detect very short ID as truncated")
    void shouldDetectVeryShortIdAsTruncated() {
      // WHEN
      final boolean truncated = ContainerIdFormatter.isTruncated("abc");

      // THEN
      assertThat(truncated).isTrue();
    }
  }
}
