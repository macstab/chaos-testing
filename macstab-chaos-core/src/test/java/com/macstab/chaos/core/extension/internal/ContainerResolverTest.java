/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link ContainerResolver}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ContainerResolver")
class ContainerResolverTest {

  @Nested
  @DisplayName("findContainerFields")
  class FindContainerFields {

    @Test
    @DisplayName("should find GenericContainer fields")
    void shouldFindGenericContainerFields() {
      final var testInstance = new TestClassWithContainers();
      final var fields = ContainerResolver.findContainerFields(testInstance.getClass());

      assertThat(fields).hasSize(2);
      assertThat(fields).extracting(Field::getName).containsExactlyInAnyOrder("redis", "postgres");
    }

    @Test
    @DisplayName("should find fields in superclass")
    void shouldFindFieldsInSuperclass() {
      final var testInstance = new TestClassWithInheritance();
      final var fields = ContainerResolver.findContainerFields(testInstance.getClass());

      assertThat(fields).hasSize(3);
      assertThat(fields)
          .extracting(Field::getName)
          .containsExactlyInAnyOrder("redis", "postgres", "mysql");
    }

    @Test
    @DisplayName("should return empty list if no containers")
    void shouldReturnEmptyListIfNoContainers() {
      final var testInstance = new TestClassWithoutContainers();
      final var fields = ContainerResolver.findContainerFields(testInstance.getClass());

      assertThat(fields).isEmpty();
    }

    @Test
    @DisplayName("should ignore non-GenericContainer fields")
    void shouldIgnoreNonGenericContainerFields() {
      final var testInstance = new TestClassWithMixedFields();
      final var fields = ContainerResolver.findContainerFields(testInstance.getClass());

      assertThat(fields).hasSize(1);
      assertThat(fields).extracting(Field::getName).containsExactly("redis");
    }
  }

  @Nested
  @DisplayName("getContainer")
  class GetContainer {

    @Test
    @DisplayName("should extract container from field")
    void shouldExtractContainerFromField() throws Exception {
      final var testInstance = new TestClassWithContainers();
      final var field = testInstance.getClass().getDeclaredField("redis");

      final var container = ContainerResolver.getContainer(field, testInstance);

      assertThat(container).isNotNull();
      assertThat(container).isSameAs(testInstance.redis);
    }

    @Test
    @DisplayName("should handle private fields")
    void shouldHandlePrivateFields() throws Exception {
      final var testInstance = new TestClassWithPrivateField();
      final var field = testInstance.getClass().getDeclaredField("redis");

      final var container = ContainerResolver.getContainer(field, testInstance);

      assertThat(container).isNotNull();
    }

    @Test
    @DisplayName("should throw if field is null")
    void shouldThrowIfFieldIsNull() throws Exception {
      final var testInstance = new TestClassWithNullContainer();
      final var field = testInstance.getClass().getDeclaredField("redis");

      assertThatThrownBy(() -> ContainerResolver.getContainer(field, testInstance))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Container field 'redis' is null");
    }
  }

  // ==================== Test Classes ====================

  @SuppressWarnings("unused")
  static class TestClassWithContainers {
    GenericContainer<?> redis = new GenericContainer<>("redis:7");
    GenericContainer<?> postgres = new GenericContainer<>("postgres:15");
  }

  @SuppressWarnings("unused")
  static class TestClassWithoutContainers {
    String name = "test";
    int value = 42;
  }

  @SuppressWarnings("unused")
  static class TestClassWithMixedFields {
    GenericContainer<?> redis = new GenericContainer<>("redis:7");
    String name = "test";
    int value = 42;
  }

  @SuppressWarnings("unused")
  static class BaseClass {
    GenericContainer<?> redis = new GenericContainer<>("redis:7");
    GenericContainer<?> postgres = new GenericContainer<>("postgres:15");
  }

  @SuppressWarnings("unused")
  static class TestClassWithInheritance extends BaseClass {
    GenericContainer<?> mysql = new GenericContainer<>("mysql:8");
  }

  @SuppressWarnings("unused")
  static class TestClassWithPrivateField {
    private GenericContainer<?> redis = new GenericContainer<>("redis:7");
  }

  @SuppressWarnings("unused")
  static class TestClassWithNullContainer {
    GenericContainer<?> redis = null;
  }
}
