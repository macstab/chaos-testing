/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RuleHandle")
class RuleHandleTest {

  @Nested
  @DisplayName("validation")
  class Validation {

    @ParameterizedTest
    @ValueSource(strings = {"a", "owner", "conn_db_5432_latency", "abc123_xyz"})
    @DisplayName("accepts owner matching [a-z0-9_]+")
    void valid(final String owner) {
      assertThat(new RuleHandle(owner).owner()).isEqualTo(owner);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "ABC",
          "owner-with-hyphen",
          "owner.with.dot",
          "owner with space",
          "owner!",
          "owner/path",
          ""
        })
    @DisplayName("rejects owner not matching [a-z0-9_]+")
    void invalid(final String owner) {
      assertThatThrownBy(() -> new RuleHandle(owner))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("[a-z0-9_]+");
    }

    @Test
    @DisplayName("null owner is rejected")
    void nullOwner() {
      assertThatThrownBy(() -> new RuleHandle(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("equality")
  class Equality {

    @Test
    @DisplayName("two handles with the same owner are equal (record contract)")
    void sameOwner() {
      assertThat(new RuleHandle("conn_db_5432")).isEqualTo(new RuleHandle("conn_db_5432"));
    }

    @Test
    @DisplayName("different owners are not equal")
    void differentOwner() {
      assertThat(new RuleHandle("a")).isNotEqualTo(new RuleHandle("b"));
    }
  }
}
