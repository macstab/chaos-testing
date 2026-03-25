/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.annotation.RequireCapability;

/**
 * Unit tests for {@link CapabilityHandler}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CapabilityHandler")
class CapabilityHandlerTest {

  private GenericContainer<?> container;
  private Field containerField;
  private TestClass testInstance;

  @BeforeEach
  void setUp() throws Exception {
    container = mock(GenericContainer.class);
    testInstance = new TestClass();
    containerField = TestClass.class.getDeclaredField("container");
    containerField.setAccessible(true);
    containerField.set(testInstance, container);
  }

  @Nested
  @DisplayName("NET_ADMIN capability validation")
  class NetAdminValidation {

    @Test
    @DisplayName("should pass when NET_ADMIN works")
    void shouldPassWhenNetAdminWorks() throws Exception {
      // GIVEN
      final ExecResult whichResult = mockExecResult(0, "/sbin/iptables", "");
      when(container.execInContainer("sh", "-c", "which iptables || echo MISSING"))
          .thenReturn(whichResult);

      final ExecResult iptablesResult = mockExecResult(0, "Chain INPUT (policy ACCEPT)", "");
      when(container.execInContainer("iptables", "-L", "-n")).thenReturn(iptablesResult);

      final RequireCapability annotation = createAnnotation(Capability.NET_ADMIN);

      // WHEN / THEN
      assertThatCode(
              () -> CapabilityHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should fail when iptables not found")
    void shouldFailWhenIptablesNotFound() throws Exception {
      // GIVEN
      final ExecResult whichResult = mockExecResult(0, "MISSING", "");
      when(container.execInContainer("sh", "-c", "which iptables || echo MISSING"))
          .thenReturn(whichResult);

      final RequireCapability annotation = createAnnotation(Capability.NET_ADMIN);

      // WHEN / THEN
      assertThatThrownBy(
              () -> CapabilityHandler.process(containerField, testInstance, List.of(annotation)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Container 'container' requires NET_ADMIN")
          .hasMessageContaining("iptables not found");
    }

    @Test
    @DisplayName("should fail when iptables command fails")
    void shouldFailWhenIptablesCommandFails() throws Exception {
      // GIVEN
      final ExecResult whichResult = mockExecResult(0, "/sbin/iptables", "");
      when(container.execInContainer("sh", "-c", "which iptables || echo MISSING"))
          .thenReturn(whichResult);

      final ExecResult iptablesResult = mockExecResult(1, "", "iptables: Permission denied");
      when(container.execInContainer("iptables", "-L", "-n")).thenReturn(iptablesResult);

      final RequireCapability annotation = createAnnotation(Capability.NET_ADMIN);

      // WHEN / THEN
      assertThatThrownBy(
              () -> CapabilityHandler.process(containerField, testInstance, List.of(annotation)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("iptables command failed")
          .hasMessageContaining("missing NET_ADMIN?");
    }

    @Test
    @DisplayName("should provide helpful error message")
    void shouldProvideHelpfulErrorMessage() throws Exception {
      // GIVEN
      final ExecResult whichResult = mockExecResult(0, "MISSING", "");
      when(container.execInContainer("sh", "-c", "which iptables || echo MISSING"))
          .thenReturn(whichResult);

      final RequireCapability annotation = createAnnotation(Capability.NET_ADMIN);

      // WHEN / THEN
      assertThatThrownBy(
              () -> CapabilityHandler.process(containerField, testInstance, List.of(annotation)))
          .hasMessageContaining("Add capability:")
          .hasMessageContaining(".withCreateContainerCmdModifier")
          .hasMessageContaining("Capability.NET_ADMIN");
    }
  }

  @Nested
  @DisplayName("multiple capabilities")
  class MultipleCapabilities {

    @Test
    @DisplayName("should validate all required capabilities")
    void shouldValidateAllRequiredCapabilities() throws Exception {
      // GIVEN
      final ExecResult whichResult = mockExecResult(0, "/sbin/iptables", "");
      when(container.execInContainer("sh", "-c", "which iptables || echo MISSING"))
          .thenReturn(whichResult);

      final ExecResult iptablesResult = mockExecResult(0, "Chain INPUT", "");
      when(container.execInContainer("iptables", "-L", "-n")).thenReturn(iptablesResult);

      final RequireCapability annotation =
          createAnnotation(Capability.NET_ADMIN, Capability.SYS_ADMIN);

      // WHEN / THEN
      assertThatCode(
              () -> CapabilityHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should deduplicate capabilities from multiple annotations")
    void shouldDeduplicateCapabilitiesFromMultipleAnnotations() throws Exception {
      // GIVEN
      final ExecResult whichResult = mockExecResult(0, "/sbin/iptables", "");
      when(container.execInContainer("sh", "-c", "which iptables || echo MISSING"))
          .thenReturn(whichResult);

      final ExecResult iptablesResult = mockExecResult(0, "Chain INPUT", "");
      when(container.execInContainer("iptables", "-L", "-n")).thenReturn(iptablesResult);

      final RequireCapability annotation1 = createAnnotation(Capability.NET_ADMIN);
      final RequireCapability annotation2 =
          createAnnotation(Capability.NET_ADMIN, Capability.SYS_ADMIN);

      // WHEN / THEN (should not validate NET_ADMIN twice)
      assertThatCode(
              () ->
                  CapabilityHandler.process(
                      containerField, testInstance, List.of(annotation1, annotation2)))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("edge cases")
  class EdgeCases {

    @Test
    @DisplayName("should handle empty annotations list")
    void shouldHandleEmptyAnnotationsList() {
      // WHEN / THEN
      assertThatCode(() -> CapabilityHandler.process(containerField, testInstance, List.of()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should handle annotations without RequireCapability")
    void shouldHandleAnnotationsWithoutRequireCapability() {
      // GIVEN
      final Annotation otherAnnotation = mock(Annotation.class);

      // WHEN / THEN
      assertThatCode(
              () ->
                  CapabilityHandler.process(containerField, testInstance, List.of(otherAnnotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject null field")
    void shouldRejectNullField() {
      // WHEN / THEN
      assertThatThrownBy(() -> CapabilityHandler.process(null, testInstance, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("field must not be null");
    }

    @Test
    @DisplayName("should reject null testInstance")
    void shouldRejectNullTestInstance() {
      // WHEN / THEN
      assertThatThrownBy(() -> CapabilityHandler.process(containerField, null, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("testInstance must not be null");
    }

    @Test
    @DisplayName("should reject null annotations")
    void shouldRejectNullAnnotations() {
      // WHEN / THEN
      assertThatThrownBy(() -> CapabilityHandler.process(containerField, testInstance, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("annotations must not be null");
    }
  }

  @Nested
  @DisplayName("exception handling")
  class ExceptionHandling {

    @Test
    @DisplayName("should handle container execution exceptions")
    void shouldHandleContainerExecutionExceptions() throws Exception {
      // GIVEN
      when(container.execInContainer(any(String.class), any(String.class), any(String.class)))
          .thenThrow(new RuntimeException("Container not running"));

      final RequireCapability annotation = createAnnotation(Capability.NET_ADMIN);

      // WHEN / THEN
      assertThatThrownBy(
              () -> CapabilityHandler.process(containerField, testInstance, List.of(annotation)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Could not validate capability")
          .hasMessageContaining("Container not running");
    }
  }

  // Test helper class
  static class TestClass {
    @SuppressWarnings("unused")
    GenericContainer<?> container;
  }

  // Helper to create RequireCapability annotation
  private RequireCapability createAnnotation(final Capability... capabilities) {
    return new RequireCapability() {
      @Override
      public Class<? extends Annotation> annotationType() {
        return RequireCapability.class;
      }

      @Override
      public Capability[] value() {
        return capabilities;
      }

      @Override
      public String target() {
        return "";
      }
    };
  }

  // Helper to create mocked ExecResult
  private ExecResult mockExecResult(final int exitCode, final String stdout, final String stderr) {
    final ExecResult result = mock(ExecResult.class);
    when(result.getExitCode()).thenReturn(exitCode);
    when(result.getStdout()).thenReturn(stdout);
    when(result.getStderr()).thenReturn(stderr);
    return result;
  }
}
