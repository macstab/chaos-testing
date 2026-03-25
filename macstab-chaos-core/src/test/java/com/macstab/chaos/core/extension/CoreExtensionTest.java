/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.annotation.InstallPackages;

/**
 * Unit tests for {@link CoreExtension}.
 *
 * <p>Tests JUnit 5 extension lifecycle, annotation processing order, and error handling.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CoreExtension")
class CoreExtensionTest {

  private CoreExtension extension;
  private ExtensionContext context;
  private Store store;

  @BeforeEach
  void setUp() {
    extension = new CoreExtension();
    context = mock(ExtensionContext.class);
    store = mock(Store.class);

    // Mock store access
    when(context.getStore(any(Namespace.class))).thenReturn(store);
  }

  @Nested
  @DisplayName("Lifecycle")
  class LifecycleTests {

    @Test
    @DisplayName("should skip processing when already processed")
    void shouldSkipProcessing_whenAlreadyProcessed() throws Exception {
      // Arrange
      when(store.get(anyString(), eq(Boolean.class))).thenReturn(true);

      // Act
      extension.beforeEach(context);

      // Assert
      verify(context, times(1)).getStore(any(Namespace.class));
      verify(context, never()).getRequiredTestClass();
      verify(context, never()).getRequiredTestInstance();
    }

    @Test
    @DisplayName("should mark as processed after successful execution")
    void shouldMarkAsProcessed_afterSuccessfulExecution() throws Exception {
      // Arrange
      when(store.get(anyString(), eq(Boolean.class))).thenReturn(false);
      when(context.getRequiredTestClass()).thenReturn((Class) NoAnnotationsTest.class);
      when(context.getRequiredTestInstance()).thenReturn(new NoAnnotationsTest());

      // Act
      extension.beforeEach(context);

      // Assert
      verify(store, atLeastOnce()).put(anyString(), eq(true));
    }

    @Test
    @DisplayName("should mark as processed only after entering try block")
    void shouldMarkAsProcessed_onlyAfterEnteringTryBlock() throws Exception {
      // Arrange - Exception BEFORE entering try block (getRequiredTestClass fails)
      when(store.get(anyString(), eq(Boolean.class))).thenReturn(false);
      when(context.getRequiredTestClass()).thenThrow(new RuntimeException("Early failure"));

      // Act & Assert - Exception prevents entering try/finally
      assertThatThrownBy(() -> extension.beforeEach(context)).isInstanceOf(RuntimeException.class);

      // Store.put NOT called because finally block was never entered
      verify(store, never()).put(anyString(), eq(true));
    }
  }

  @Nested
  @DisplayName("Annotation Processing")
  class AnnotationProcessingTests {

    @Test
    @DisplayName("should process test class without annotations")
    void shouldProcessTestClass_withoutAnnotations() throws Exception {
      // Arrange
      when(store.get(anyString(), eq(Boolean.class))).thenReturn(false);
      when(context.getRequiredTestClass()).thenReturn((Class) NoAnnotationsTest.class);
      when(context.getRequiredTestInstance()).thenReturn(new NoAnnotationsTest());

      // Act
      extension.beforeEach(context);

      // Assert
      verify(context).getRequiredTestClass();
      verify(context).getRequiredTestInstance();
      verify(store, atLeastOnce()).put(anyString(), eq(true));
    }

    @Test
    @DisplayName("should handle null test instance gracefully")
    void shouldHandleNullTestInstance_gracefully() {
      // Arrange
      when(store.get(anyString(), eq(Boolean.class))).thenReturn(false);
      when(context.getRequiredTestClass()).thenReturn((Class) NoAnnotationsTest.class);
      when(context.getRequiredTestInstance()).thenThrow(new IllegalStateException("No instance"));

      // Act & Assert
      assertThatThrownBy(() -> extension.beforeEach(context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No instance");
    }

    @Test
    @DisplayName("should handle missing test class gracefully")
    void shouldHandleMissingTestClass_gracefully() {
      // Arrange
      when(store.get(anyString(), eq(Boolean.class))).thenReturn(false);
      when(context.getRequiredTestClass()).thenThrow(new IllegalStateException("No class"));

      // Act & Assert
      assertThatThrownBy(() -> extension.beforeEach(context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No class");
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandlingTests {

    @Test
    @DisplayName("should wrap exceptions with descriptive message")
    void shouldWrapExceptions_withDescriptiveMessage() {
      // Arrange
      when(store.get(anyString(), eq(Boolean.class))).thenReturn(false);
      when(context.getRequiredTestClass()).thenReturn((Class) InvalidAnnotationsTest.class);
      when(context.getRequiredTestInstance()).thenReturn(new InvalidAnnotationsTest());

      // Act & Assert
      assertThatThrownBy(() -> extension.beforeEach(context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Core annotation processing failed");
    }

    @Test
    @DisplayName("should not double-wrap exceptions")
    void shouldNotDoubleWrapExceptions() {
      // Arrange
      when(store.get(anyString(), eq(Boolean.class))).thenReturn(false);
      when(context.getRequiredTestClass()).thenReturn((Class) NoAnnotationsTest.class);
      when(context.getRequiredTestInstance()).thenThrow(new IllegalStateException("Test error"));

      // Act & Assert
      assertThatThrownBy(() -> extension.beforeEach(context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Test error");
    }
  }

  // Test classes for scenarios
  static class NoAnnotationsTest {
    // No annotations
  }

  static class InvalidAnnotationsTest {
    @InstallPackages("invalid-package-that-does-not-exist")
    GenericContainer<?> container;
  }
}
