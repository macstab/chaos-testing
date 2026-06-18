/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link TargetMatcher}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("TargetMatcher")
class TargetMatcherTest {

  @Nested
  @DisplayName("matches")
  class Matches {

    @Test
    @DisplayName("should match empty target to any container")
    void shouldMatchEmptyTargetToAnyContainer() throws Exception {
      final var field = TestClass.class.getDeclaredField("redis");
      final var testInstance = new TestClass();
      final var container = (GenericContainer<?>) field.get(testInstance);

      final boolean result = TargetMatcher.matches("", field, container);

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("should match target to container id via annotation")
    void shouldMatchTargetToContainerIdViaAnnotation() throws Exception {
      final var field = TestClassWithIdAnnotation.class.getDeclaredField("master");
      final var testInstance = new TestClassWithIdAnnotation();
      final var container = (GenericContainer<?>) field.get(testInstance);

      final boolean result = TargetMatcher.matches("master", field, container);

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("should not match different container id")
    void shouldNotMatchDifferentContainerId() throws Exception {
      final var field = TestClassWithIdAnnotation.class.getDeclaredField("master");
      final var testInstance = new TestClassWithIdAnnotation();
      final var container = (GenericContainer<?>) field.get(testInstance);

      final boolean result = TargetMatcher.matches("replica", field, container);

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should match target to field name")
    void shouldMatchTargetToFieldName() throws Exception {
      final var field = TestClass.class.getDeclaredField("redis");
      final var testInstance = new TestClass();
      final var container = (GenericContainer<?>) field.get(testInstance);

      final boolean result = TargetMatcher.matches("redis", field, container);

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("should not match different field name")
    void shouldNotMatchDifferentFieldName() throws Exception {
      final var field = TestClass.class.getDeclaredField("redis");
      final var testInstance = new TestClass();
      final var container = (GenericContainer<?>) field.get(testInstance);

      final boolean result = TargetMatcher.matches("postgres", field, container);

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should be case-sensitive for field name")
    void shouldBeCaseSensitiveForFieldName() throws Exception {
      final var field = TestClass.class.getDeclaredField("redis");
      final var testInstance = new TestClass();
      final var container = (GenericContainer<?>) field.get(testInstance);

      final boolean result = TargetMatcher.matches("Redis", field, container);

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should prefer container id over field name")
    void shouldPreferContainerIdOverFieldName() throws Exception {
      final var field = TestClassWithIdAnnotation.class.getDeclaredField("master");
      final var testInstance = new TestClassWithIdAnnotation();
      final var container = (GenericContainer<?>) field.get(testInstance);

      // Target "master" should match container id from annotation
      final boolean result = TargetMatcher.matches("master", field, container);

      assertThat(result).isTrue();
    }
  }

  // ==================== Test Annotations ====================

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
  @interface TestContainerAnnotation {
    String id() default "";
  }

  // ==================== Test Classes ====================

  @SuppressWarnings("unused")
  static class TestClass {
    GenericContainer<?> redis = new GenericContainer<>("redis:7");
  }

  @SuppressWarnings("unused")
  static class TestClassWithIdAnnotation {
    @TestContainerAnnotation(id = "master")
    GenericContainer<?> master = new GenericContainer<>("redis:7");
  }
}
