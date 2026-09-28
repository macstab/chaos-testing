/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
  @DisplayName("requireNetworkModule()")
  class RequireNetworkModuleTests {

    @Test
    @DisplayName("Should not throw when network module is on the classpath")
    void shouldNotThrowWhenNetworkModulePresent() {
      // ARRANGE: macstab-chaos-network is a testImplementation dependency of this module
      // ACT & ASSERT
      assertThatCode(DependencyVerifier::requireNetworkModule).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should guard the class that enableNetworkChaos actually needs at runtime")
    void shouldGuardNetworkChaosController() {
      // ARRANGE: ControlFacade.create() instantiates NetworkChaosController for tc/netem chaos
      // ACT & ASSERT
      assertThat(
              DependencyVerifier.isPresent(
                  "com.macstab.chaos.network.control.NetworkChaosController"))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("requireConnectionModule()")
  class RequireConnectionModuleTests {

    @Test
    @DisplayName("Should not throw when connection module is on the classpath")
    void shouldNotThrowWhenConnectionModulePresent() {
      // ARRANGE: macstab-chaos-connection is a testImplementation dependency of this module
      // ACT & ASSERT
      assertThatCode(DependencyVerifier::requireConnectionModule).doesNotThrowAnyException();
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
