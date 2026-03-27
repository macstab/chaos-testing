/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.MockChaosPlugin.MockContainer;

/**
 * Unit tests for {@link ChaosTestingExtension} annotation extraction logic.
 *
 * <p>Tests validate the {@code extractContainerAnnotations()} and {@code extractId()} 
 * private methods using real @MockContainer annotations that are registered in the plugin system.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ChaosTestingExtension - Annotation Extraction")
class ChaosTestingExtensionAnnotationExtractionTest {

  /** Single annotation. */
  @MockContainer(image = "alpine:latest", port = 8080)
  static class SingleAnnotationClass {}

  /** No annotations. */
  static class NoAnnotationsClass {}

  @Nested
  @DisplayName("extractContainerAnnotations() - Plugin Annotation Discovery")
  class ExtractContainerAnnotationsTest {

    @Test
    @DisplayName("Should extract single plugin annotation")
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
      MockContainer annotation = (MockContainer) result.get(0);
      assertThat(annotation.image()).isEqualTo("alpine:latest");
      assertThat(annotation.port()).isEqualTo(8080);
    }

    @Test
    @DisplayName("Should return empty list when no plugin annotations found")
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
    @DisplayName("Should extract 'default' when using default id()")
    void shouldExtractDefaultId() throws Exception {
      // ARRANGE
      Method extractIdMethod = findExtractIdMethod();
      ChaosTestingExtension extension = new ChaosTestingExtension();
      MockContainer annotation = SingleAnnotationClass.class.getAnnotation(MockContainer.class);

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
