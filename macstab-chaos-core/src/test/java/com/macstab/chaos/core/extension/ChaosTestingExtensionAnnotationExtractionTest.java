/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChaosTestingExtension} repeatable annotation extraction logic.
 *
 * <p>Tests validate the critical {@code extractContainerAnnotations()} method that handles
 * Java's {@code @Repeatable} annotation wrapping behavior.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ChaosTestingExtension - Annotation Extraction")
class ChaosTestingExtensionAnnotationExtractionTest {

  /** Test annotation for repeatable pattern. */
  @Retention(RetentionPolicy.RUNTIME)
  @java.lang.annotation.Repeatable(TestAnnotations.class)
  @interface TestAnnotation {
    String id() default "default";
  }

  /** Container for repeatable annotations. */
  @Retention(RetentionPolicy.RUNTIME)
  @interface TestAnnotations {
    TestAnnotation[] value();
  }

  /** Single annotation (not wrapped). */
  @TestAnnotation(id = "single")
  static class SingleAnnotationClass {}

  /** Multiple annotations (wrapped in container). */
  @TestAnnotation(id = "first")
  @TestAnnotation(id = "second")
  @TestAnnotation(id = "third")
  static class MultipleAnnotationsClass {}

  /** No annotations. */
  static class NoAnnotationsClass {}

  /** Annotation without id() method (for edge case testing). */
  @Retention(RetentionPolicy.RUNTIME)
  @interface NoIdAnnotation {}

  /** Test class with NoIdAnnotation. */
  @NoIdAnnotation
  static class NoIdClass {}

  @Nested
  @DisplayName("extractContainerAnnotations() - Repeatable Pattern")
  class ExtractContainerAnnotationsTest {

    @Test
    @DisplayName("Should extract single annotation without container wrapping")
    void shouldExtractSingleAnnotation() throws Exception {
      // ARRANGE
      Method extractMethod = findExtractMethod();
      ChaosTestingExtension extension = new ChaosTestingExtension();

      // ACT
      @SuppressWarnings("unchecked")
      List<Annotation> result =
          (List<Annotation>) extractMethod.invoke(extension, SingleAnnotationClass.class);

      // ASSERT
      assertThat(result).hasSize(1);
      TestAnnotation annotation = (TestAnnotation) result.get(0);
      assertThat(annotation.id()).isEqualTo("single");
    }

    @Test
    @DisplayName("Should extract multiple annotations from container")
    void shouldExtractMultipleFromContainer() throws Exception {
      // ARRANGE
      Method extractMethod = findExtractMethod();
      ChaosTestingExtension extension = new ChaosTestingExtension();

      // ACT
      @SuppressWarnings("unchecked")
      List<Annotation> result =
          (List<Annotation>) extractMethod.invoke(extension, MultipleAnnotationsClass.class);

      // ASSERT
      assertThat(result).hasSize(3);
      TestAnnotation first = (TestAnnotation) result.get(0);
      TestAnnotation second = (TestAnnotation) result.get(1);
      TestAnnotation third = (TestAnnotation) result.get(2);
      assertThat(first.id()).isEqualTo("first");
      assertThat(second.id()).isEqualTo("second");
      assertThat(third.id()).isEqualTo("third");
    }

    @Test
    @DisplayName("Should return empty list when no annotations found")
    void shouldReturnEmptyWhenNoAnnotations() throws Exception {
      // ARRANGE
      Method extractMethod = findExtractMethod();
      ChaosTestingExtension extension = new ChaosTestingExtension();

      // ACT
      @SuppressWarnings("unchecked")
      List<Annotation> result =
          (List<Annotation>) extractMethod.invoke(extension, NoAnnotationsClass.class);

      // ASSERT
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should maintain declaration order from multiple annotations")
    void shouldMaintainDeclarationOrder() throws Exception {
      // ARRANGE
      Method extractMethod = findExtractMethod();
      ChaosTestingExtension extension = new ChaosTestingExtension();

      // ACT
      @SuppressWarnings("unchecked")
      List<Annotation> result =
          (List<Annotation>) extractMethod.invoke(extension, MultipleAnnotationsClass.class);

      // ASSERT - Order should match declaration (first, second, third)
      assertThat(result)
          .extracting(a -> ((TestAnnotation) a).id())
          .containsExactly("first", "second", "third");
    }

    private Method findExtractMethod() throws Exception {
      Method method =
          ChaosTestingExtension.class.getDeclaredMethod(
              "extractContainerAnnotations", Class.class);
      method.setAccessible(true);
      return method;
    }
  }

  @Nested
  @DisplayName("extractId() - ID Extraction")
  class ExtractIdTest {

    @Test
    @DisplayName("Should extract ID from annotation")
    void shouldExtractId() throws Exception {
      // ARRANGE
      Method extractIdMethod = findExtractIdMethod();
      ChaosTestingExtension extension = new ChaosTestingExtension();
      TestAnnotation annotation = SingleAnnotationClass.class.getAnnotation(TestAnnotation.class);

      // ACT
      String result = (String) extractIdMethod.invoke(extension, annotation);

      // ASSERT
      assertThat(result).isEqualTo("single");
    }

    @Test
    @DisplayName("Should return 'default' when no id() method exists")
    void shouldReturnDefaultWhenNoIdMethod() throws Exception {
      // ARRANGE
      Method extractIdMethod = findExtractIdMethod();
      ChaosTestingExtension extension = new ChaosTestingExtension();
      NoIdAnnotation annotation = NoIdClass.class.getAnnotation(NoIdAnnotation.class);

      // ACT
      String result = (String) extractIdMethod.invoke(extension, annotation);

      // ASSERT
      assertThat(result).isEqualTo("default");
    }

    private Method findExtractIdMethod() throws Exception {
      Method method =
          ChaosTestingExtension.class.getDeclaredMethod("extractId", Annotation.class);
      method.setAccessible(true);
      return method;
    }
  }
}
