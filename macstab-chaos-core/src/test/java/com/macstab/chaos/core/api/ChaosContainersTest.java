/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosTestingExtension;

/**
 * Unit tests for {@link ChaosContainers} programmatic access API.
 *
 * <p>Tests validate:
 * <ul>
 *   <li>ThreadLocal registry access
 *   <li>Error handling (not found, no extension active)
 *   <li>Type-based retrieval
 *   <li>Base type retrieval
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ChaosContainers - Programmatic Access API")
class ChaosContainersTest {

  /** Test annotation for retrieval. */
  @interface TestAnnotation {}

  /** Test connection info class. */
  static class TestConnectionInfo {
    final String value;

    TestConnectionInfo(String value) {
      this.value = value;
    }
  }

  /** Base interface for testing. */
  interface TestBase {}

  /** Implementation for testing base type retrieval. */
  static class TestImpl implements TestBase {
    final String value;

    TestImpl(String value) {
      this.value = value;
    }
  }

  @BeforeEach
  void setUp() {
    // Clear ThreadLocal before each test
    cleanThreadLocal();
  }

  @AfterEach
  void tearDown() {
    // Clean up ThreadLocal after each test
    cleanThreadLocal();
  }

  private void cleanThreadLocal() {
    try {
      // Access private ThreadLocal via reflection for cleanup
      java.lang.reflect.Field byAnnotation =
          ChaosTestingExtension.class.getDeclaredField("CONNECTION_INFO_BY_ANNOTATION");
      byAnnotation.setAccessible(true);
      ThreadLocal<?> tlByAnnotation = (ThreadLocal<?>) byAnnotation.get(null);
      tlByAnnotation.remove();

      java.lang.reflect.Field byBaseType =
          ChaosTestingExtension.class.getDeclaredField("CONNECTION_INFO_BY_BASE_TYPE");
      byBaseType.setAccessible(true);
      ThreadLocal<?> tlByBaseType = (ThreadLocal<?>) byBaseType.get(null);
      tlByBaseType.remove();
    } catch (Exception e) {
      throw new RuntimeException("Failed to clean ThreadLocal", e);
    }
  }

  private void setupRegistry(
      Map<Class<? extends Annotation>, Map<String, Object>> byAnnotation,
      Map<Class<?>, Map<String, Object>> byBaseType) {
    try {
      java.lang.reflect.Field byAnnotationField =
          ChaosTestingExtension.class.getDeclaredField("CONNECTION_INFO_BY_ANNOTATION");
      byAnnotationField.setAccessible(true);
      @SuppressWarnings("unchecked")
      ThreadLocal<Map<Class<? extends Annotation>, Map<String, Object>>> tlByAnnotation =
          (ThreadLocal<Map<Class<? extends Annotation>, Map<String, Object>>>)
              byAnnotationField.get(null);
      tlByAnnotation.set(byAnnotation);

      java.lang.reflect.Field byBaseTypeField =
          ChaosTestingExtension.class.getDeclaredField("CONNECTION_INFO_BY_BASE_TYPE");
      byBaseTypeField.setAccessible(true);
      @SuppressWarnings("unchecked")
      ThreadLocal<Map<Class<?>, Map<String, Object>>> tlByBaseType =
          (ThreadLocal<Map<Class<?>, Map<String, Object>>>) byBaseTypeField.get(null);
      tlByBaseType.set(byBaseType);
    } catch (Exception e) {
      throw new RuntimeException("Failed to setup registry", e);
    }
  }

  @Nested
  @DisplayName("get() - Single Instance Retrieval")
  class GetSingleTest {

    @Test
    @DisplayName("Should retrieve connection info by annotation type and ID")
    void shouldGetByAnnotationAndId() {
      // ARRANGE
      TestConnectionInfo expected = new TestConnectionInfo("test-value");
      Map<Class<? extends Annotation>, Map<String, Object>> byAnnotation = new HashMap<>();
      Map<String, Object> byId = new HashMap<>();
      byId.put("test-id", expected);
      byAnnotation.put(TestAnnotation.class, byId);
      setupRegistry(byAnnotation, new HashMap<>());

      // ACT
      Object actual = ChaosContainers.get(TestAnnotation.class, "test-id");

      // ASSERT
      assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("Should throw NoSuchElementException when annotation type not found")
    void shouldThrowWhenAnnotationNotFound() {
      // ARRANGE
      setupRegistry(new HashMap<>(), new HashMap<>());

      // ACT & ASSERT
      assertThatThrownBy(() -> ChaosContainers.get(TestAnnotation.class, "test-id"))
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("No containers found for @TestAnnotation");
    }

    @Test
    @DisplayName("Should throw NoSuchElementException when ID not found")
    void shouldThrowWhenIdNotFound() {
      // ARRANGE
      Map<Class<? extends Annotation>, Map<String, Object>> byAnnotation = new HashMap<>();
      byAnnotation.put(TestAnnotation.class, new HashMap<>());
      setupRegistry(byAnnotation, new HashMap<>());

      // ACT & ASSERT
      assertThatThrownBy(() -> ChaosContainers.get(TestAnnotation.class, "missing-id"))
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("No container found for @TestAnnotation(id=\"missing-id\")");
    }
  }

  @Nested
  @DisplayName("getAll() - All Instances Retrieval")
  class GetAllTest {

    @Test
    @DisplayName("Should retrieve all connection info for annotation type")
    void shouldGetAllByAnnotation() {
      // ARRANGE
      TestConnectionInfo first = new TestConnectionInfo("first");
      TestConnectionInfo second = new TestConnectionInfo("second");
      Map<Class<? extends Annotation>, Map<String, Object>> byAnnotation = new HashMap<>();
      Map<String, Object> byId = new HashMap<>();
      byId.put("first", first);
      byId.put("second", second);
      byAnnotation.put(TestAnnotation.class, byId);
      setupRegistry(byAnnotation, new HashMap<>());

      // ACT
      List<Object> actual = ChaosContainers.getAll(TestAnnotation.class);

      // ASSERT
      assertThat(actual).hasSize(2);
      assertThat(actual).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("Should return empty list when annotation type not found")
    void shouldReturnEmptyWhenAnnotationNotFound() {
      // ARRANGE
      setupRegistry(new HashMap<>(), new HashMap<>());

      // ACT
      List<Object> actual = ChaosContainers.getAll(TestAnnotation.class);

      // ASSERT
      assertThat(actual).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list when no instances registered")
    void shouldReturnEmptyWhenNoInstances() {
      // ARRANGE
      Map<Class<? extends Annotation>, Map<String, Object>> byAnnotation = new HashMap<>();
      byAnnotation.put(TestAnnotation.class, new HashMap<>());
      setupRegistry(byAnnotation, new HashMap<>());

      // ACT
      List<Object> actual = ChaosContainers.getAll(TestAnnotation.class);

      // ASSERT
      assertThat(actual).isEmpty();
    }
  }

  @Nested
  @DisplayName("getAllByBaseType() - Base Type Retrieval")
  class GetAllByBaseTypeTest {

    @Test
    @DisplayName("Should retrieve all instances by base type")
    void shouldGetAllByBaseType() {
      // ARRANGE
      TestImpl first = new TestImpl("first");
      TestImpl second = new TestImpl("second");
      Map<Class<?>, Map<String, Object>> byBaseType = new HashMap<>();
      Map<String, Object> byId = new HashMap<>();
      byId.put("first", first);
      byId.put("second", second);
      byBaseType.put(TestBase.class, byId);
      setupRegistry(new HashMap<>(), byBaseType);

      // ACT
      List<TestBase> actual = ChaosContainers.getAllByBaseType(TestBase.class);

      // ASSERT
      assertThat(actual).hasSize(2);
      assertThat(actual).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("Should return empty list when base type not found")
    void shouldReturnEmptyWhenBaseTypeNotFound() {
      // ARRANGE
      setupRegistry(new HashMap<>(), new HashMap<>());

      // ACT
      List<TestBase> actual = ChaosContainers.getAllByBaseType(TestBase.class);

      // ASSERT
      assertThat(actual).isEmpty();
    }
  }

  @Nested
  @DisplayName("getByBaseType() - Single Base Type Retrieval")
  class GetByBaseTypeTest {

    @Test
    @DisplayName("Should retrieve instance by base type and ID")
    void shouldGetByBaseTypeAndId() {
      // ARRANGE
      TestImpl expected = new TestImpl("test-value");
      Map<Class<?>, Map<String, Object>> byBaseType = new HashMap<>();
      Map<String, Object> byId = new HashMap<>();
      byId.put("test-id", expected);
      byBaseType.put(TestBase.class, byId);
      setupRegistry(new HashMap<>(), byBaseType);

      // ACT
      Object actual = ChaosContainers.getByBaseType(TestBase.class, "test-id");

      // ASSERT
      assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("Should throw NoSuchElementException when base type not found")
    void shouldThrowWhenBaseTypeNotFound() {
      // ARRANGE
      setupRegistry(new HashMap<>(), new HashMap<>());

      // ACT & ASSERT
      assertThatThrownBy(() -> ChaosContainers.getByBaseType(TestBase.class, "test-id"))
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("No containers found implementing TestBase");
    }

    @Test
    @DisplayName("Should throw NoSuchElementException when ID not found for base type")
    void shouldThrowWhenIdNotFoundForBaseType() {
      // ARRANGE
      Map<Class<?>, Map<String, Object>> byBaseType = new HashMap<>();
      byBaseType.put(TestBase.class, new HashMap<>());
      setupRegistry(new HashMap<>(), byBaseType);

      // ACT & ASSERT
      assertThatThrownBy(() -> ChaosContainers.getByBaseType(TestBase.class, "missing-id"))
          .isInstanceOf(java.util.NoSuchElementException.class)
          .hasMessageContaining("No container found implementing TestBase with id=\"missing-id\"");
    }
  }
}
