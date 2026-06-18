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
 * Unit tests for {@link ContainerManager} type-safe facade.
 *
 * <p>Tests validate:
 *
 * <ul>
 *   <li>Type-safe wrapper methods
 *   <li>Generic type safety
 *   <li>Delegation to ChaosContainers
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ContainerManager - Type-Safe Facade")
class ContainerManagerTest {

  /** Test annotation for retrieval. */
  @interface TestAnnotation {}

  /** Test connection info class. */
  static class TestConnectionInfo {
    final String value;

    TestConnectionInfo(String value) {
      this.value = value;
    }
  }

  @BeforeEach
  void setUp() {
    cleanThreadLocal();
  }

  @AfterEach
  void tearDown() {
    cleanThreadLocal();
  }

  private void cleanThreadLocal() {
    try {
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

  private void setupRegistry(Map<Class<? extends Annotation>, Map<String, Object>> byAnnotation) {
    try {
      java.lang.reflect.Field byAnnotationField =
          ChaosTestingExtension.class.getDeclaredField("CONNECTION_INFO_BY_ANNOTATION");
      byAnnotationField.setAccessible(true);
      @SuppressWarnings("unchecked")
      ThreadLocal<Map<Class<? extends Annotation>, Map<String, Object>>> tlByAnnotation =
          (ThreadLocal<Map<Class<? extends Annotation>, Map<String, Object>>>)
              byAnnotationField.get(null);
      tlByAnnotation.set(byAnnotation);
    } catch (Exception e) {
      throw new RuntimeException("Failed to setup registry", e);
    }
  }

  @Nested
  @DisplayName("get() - Type-Safe Single Retrieval")
  class GetTest {

    @Test
    @DisplayName("Should retrieve connection info with type safety")
    void shouldGetTypeSafely() {
      // ARRANGE
      TestConnectionInfo expected = new TestConnectionInfo("test-value");
      Map<Class<? extends Annotation>, Map<String, Object>> byAnnotation = new HashMap<>();
      Map<String, Object> byId = new HashMap<>();
      byId.put("test-id", expected);
      byAnnotation.put(TestAnnotation.class, byId);
      setupRegistry(byAnnotation);

      ContainerManager<TestConnectionInfo> manager =
          new ContainerManager<>(
              id -> ChaosContainers.get(TestAnnotation.class, id),
              () -> ChaosContainers.getAll(TestAnnotation.class));

      // ACT
      @SuppressWarnings("unchecked")
      TestConnectionInfo actual = (TestConnectionInfo) manager.get("test-id");

      // ASSERT
      assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("Should throw when not found")
    void shouldThrowWhenNotFound() {
      // ARRANGE
      setupRegistry(new HashMap<>());
      ContainerManager<Object> manager =
          new ContainerManager<>(
              id -> ChaosContainers.get(TestAnnotation.class, id),
              () -> ChaosContainers.getAll(TestAnnotation.class));

      // ACT & ASSERT
      assertThatThrownBy(() -> manager.get("missing-id"))
          .isInstanceOf(java.util.NoSuchElementException.class);
    }
  }

  @Nested
  @DisplayName("getAll() - Type-Safe List Retrieval")
  class GetAllTest {

    @Test
    @DisplayName("Should retrieve all instances with type safety")
    void shouldGetAllTypeSafely() {
      // ARRANGE
      TestConnectionInfo first = new TestConnectionInfo("first");
      TestConnectionInfo second = new TestConnectionInfo("second");
      Map<Class<? extends Annotation>, Map<String, Object>> byAnnotation = new HashMap<>();
      Map<String, Object> byId = new HashMap<>();
      byId.put("first", first);
      byId.put("second", second);
      byAnnotation.put(TestAnnotation.class, byId);
      setupRegistry(byAnnotation);

      ContainerManager<Object> manager =
          new ContainerManager<>(
              id -> ChaosContainers.get(TestAnnotation.class, id),
              () -> ChaosContainers.getAll(TestAnnotation.class));

      // ACT
      @SuppressWarnings("unchecked")
      List<TestConnectionInfo> actual = (List<TestConnectionInfo>) (List<?>) manager.getAll();

      // ASSERT
      assertThat(actual).hasSize(2);
      assertThat(actual).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("Should return empty list when none found")
    void shouldReturnEmptyWhenNoneFound() {
      // ARRANGE
      setupRegistry(new HashMap<>());
      ContainerManager<Object> manager =
          new ContainerManager<>(
              id -> ChaosContainers.get(TestAnnotation.class, id),
              () -> ChaosContainers.getAll(TestAnnotation.class));

      // ACT
      List<Object> actual = manager.getAll();

      // ASSERT
      assertThat(actual).isEmpty();
    }
  }

  @Nested
  @DisplayName("Type Safety Validation")
  class TypeSafetyTest {

    @Test
    @DisplayName("Should provide compile-time type safety")
    void shouldProvideTypeSafety() {
      // ARRANGE
      TestConnectionInfo expected = new TestConnectionInfo("test");
      Map<Class<? extends Annotation>, Map<String, Object>> byAnnotation = new HashMap<>();
      Map<String, Object> byId = new HashMap<>();
      byId.put("id", expected);
      byAnnotation.put(TestAnnotation.class, byId);
      setupRegistry(byAnnotation);

      ContainerManager<TestConnectionInfo> manager =
          new ContainerManager<>(
              id -> {
                @SuppressWarnings("unchecked")
                TestConnectionInfo result =
                    (TestConnectionInfo) ChaosContainers.get(TestAnnotation.class, id);
                return result;
              },
              () -> {
                @SuppressWarnings("unchecked")
                List<TestConnectionInfo> result =
                    (List<TestConnectionInfo>)
                        (List<?>) ChaosContainers.getAll(TestAnnotation.class);
                return result;
              });

      // ACT
      TestConnectionInfo actual = manager.get("id");
      List<TestConnectionInfo> all = manager.getAll();

      // ASSERT - No cast needed in user code (casting handled in manager setup)
      assertThat(actual.value).isEqualTo("test");
      assertThat(all.get(0).value).isEqualTo("test");
    }
  }
}
