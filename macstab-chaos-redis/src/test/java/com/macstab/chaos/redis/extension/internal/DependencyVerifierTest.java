/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DependencyVerifier}. */
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
    @DisplayName("Should return true for java.lang.Object")
    void shouldReturnTrueForObject() {
      assertThat(DependencyVerifier.isPresent("java.lang.Object")).isTrue();
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

  @Nested
  @DisplayName("Constructor guard")
  class ConstructorGuardTests {

    @Test
    @DisplayName("Constructor throws UnsupportedOperationException via reflection")
    void shouldThrowOnReflectiveInstantiation() throws Exception {
      // ARRANGE
      final Constructor<DependencyVerifier> ctor =
          DependencyVerifier.class.getDeclaredConstructor();
      ctor.setAccessible(true);

      // ACT & ASSERT
      assertThatThrownBy(ctor::newInstance)
          .isInstanceOf(InvocationTargetException.class)
          .hasCauseInstanceOf(UnsupportedOperationException.class)
          .hasRootCauseMessage("Utility class - not instantiable");
    }
  }
}
