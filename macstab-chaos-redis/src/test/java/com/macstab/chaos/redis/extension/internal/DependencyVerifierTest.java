/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DependencyVerifier}.
 */
@DisplayName("DependencyVerifier")
class DependencyVerifierTest {

  @Nested
  @DisplayName("isPresent()")
  class IsPresentTests {

    @Test
    @DisplayName("Should return true for a class on the classpath")
    void shouldReturnTrueForPresentClass() {
      // ARRANGE: java.lang.String is always on the classpath
      // ACT & ASSERT
      assertThat(DependencyVerifier.isPresent("java.lang.String")).isTrue();
    }

    @Test
    @DisplayName("Should return false for a class not on the classpath")
    void shouldReturnFalseForAbsentClass() {
      assertThat(DependencyVerifier.isPresent("com.nonexistent.SomeClass")).isFalse();
    }

    @Test
    @DisplayName("Should return false for empty class name")
    void shouldReturnFalseForEmptyName() {
      assertThat(DependencyVerifier.isPresent("")).isFalse();
    }
  }

  @Nested
  @DisplayName("requireCacheModule()")
  class RequireCacheModuleTests {

    @Test
    @DisplayName("Should throw ISE when cache module is absent")
    void shouldThrowWhenCacheModuleAbsent() {
      // ARRANGE: The cache module is NOT on the test classpath
      // ACT & ASSERT
      assertThatThrownBy(DependencyVerifier::requireCacheModule)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("RedisCacheChaosProvider")
          .hasMessageContaining("macstab-chaos-cache");
    }
  }
}
