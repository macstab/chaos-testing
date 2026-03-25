/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.macstab.chaos.core.annotation.ConfigureContainer;

/**
 * Unit tests for {@link ContainerConfigHandler}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ContainerConfigHandler")
class ContainerConfigHandlerTest {

  private GenericContainer<?> container;
  private Field containerField;
  private TestClass testInstance;
  private DockerClient dockerClient;
  private InspectContainerCmd inspectCmd;
  private InspectContainerResponse inspectResponse;
  private HostConfig hostConfig;

  @BeforeEach
  void setUp() throws Exception {
    container = mock(GenericContainer.class);
    dockerClient = mock(DockerClient.class);
    inspectCmd = mock(InspectContainerCmd.class);
    inspectResponse = mock(InspectContainerResponse.class);
    hostConfig = mock(HostConfig.class);

    testInstance = new TestClass();
    containerField = TestClass.class.getDeclaredField("container");
    containerField.setAccessible(true);
    containerField.set(testInstance, container);

    when(container.getContainerId()).thenReturn("test-container-id");
    when(container.getDockerClient()).thenReturn(dockerClient);
    when(dockerClient.inspectContainerCmd("test-container-id")).thenReturn(inspectCmd);
    when(inspectCmd.exec()).thenReturn(inspectResponse);
    when(inspectResponse.getHostConfig()).thenReturn(hostConfig);
  }

  @Nested
  @DisplayName("memory validation")
  class MemoryValidation {

    @Test
    @DisplayName("should pass when memory limit is sufficient")
    void shouldPassWhenMemoryLimitIsSufficient() {
      // GIVEN
      when(hostConfig.getMemory()).thenReturn(512L * 1024 * 1024); // 512M

      final ConfigureContainer annotation = createAnnotation("512M", 0, 0, "");

      // WHEN / THEN
      assertThatCode(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should pass when memory limit exceeds requirement")
    void shouldPassWhenMemoryLimitExceedsRequirement() {
      // GIVEN
      when(hostConfig.getMemory()).thenReturn(1024L * 1024 * 1024); // 1G

      final ConfigureContainer annotation = createAnnotation("512M", 0, 0, "");

      // WHEN / THEN
      assertThatCode(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should fail when memory limit is insufficient")
    void shouldFailWhenMemoryLimitIsInsufficient() {
      // GIVEN
      when(hostConfig.getMemory()).thenReturn(256L * 1024 * 1024); // 256M

      final ConfigureContainer annotation = createAnnotation("512M", 0, 0, "");

      // WHEN / THEN
      assertThatThrownBy(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Container 'container' configuration validation failed")
          .hasMessageContaining("memory")
          .hasMessageContaining("less than required");
    }

    @Test
    @DisplayName("should fail when memory limit is not set")
    void shouldFailWhenMemoryLimitIsNotSet() {
      // GIVEN
      when(hostConfig.getMemory()).thenReturn(null);

      final ConfigureContainer annotation = createAnnotation("512M", 0, 0, "");

      // WHEN / THEN
      assertThatThrownBy(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Container has no memory limit set")
          .hasMessageContaining("withMemory");
    }

    @Test
    @DisplayName("should parse memory in kilobytes")
    void shouldParseMemoryInKilobytes() {
      // GIVEN
      when(hostConfig.getMemory()).thenReturn(512L * 1024); // 512K

      final ConfigureContainer annotation = createAnnotation("512K", 0, 0, "");

      // WHEN / THEN
      assertThatCode(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should parse memory in gigabytes")
    void shouldParseMemoryInGigabytes() {
      // GIVEN
      when(hostConfig.getMemory()).thenReturn(2L * 1024 * 1024 * 1024); // 2G

      final ConfigureContainer annotation = createAnnotation("2G", 0, 0, "");

      // WHEN / THEN
      assertThatCode(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("CPU validation")
  class CpuValidation {

    @Test
    @DisplayName("should pass when CPU count is sufficient")
    void shouldPassWhenCpuCountIsSufficient() {
      // GIVEN
      when(hostConfig.getCpuCount()).thenReturn(4L);

      final ConfigureContainer annotation = createAnnotation("", 4, 0, "");

      // WHEN / THEN
      assertThatCode(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should pass when CPU count exceeds requirement")
    void shouldPassWhenCpuCountExceedsRequirement() {
      // GIVEN
      when(hostConfig.getCpuCount()).thenReturn(8L);

      final ConfigureContainer annotation = createAnnotation("", 4, 0, "");

      // WHEN / THEN
      assertThatCode(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should fail when CPU count is insufficient")
    void shouldFailWhenCpuCountIsInsufficient() {
      // GIVEN
      when(hostConfig.getCpuCount()).thenReturn(2L);

      final ConfigureContainer annotation = createAnnotation("", 4, 0, "");

      // WHEN / THEN
      assertThatThrownBy(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Container 'container' configuration validation failed")
          .hasMessageContaining("cpus")
          .hasMessageContaining("less than required");
    }

    @Test
    @DisplayName("should fail when CPU count is not set")
    void shouldFailWhenCpuCountIsNotSet() {
      // GIVEN
      when(hostConfig.getCpuCount()).thenReturn(null);

      final ConfigureContainer annotation = createAnnotation("", 4, 0, "");

      // WHEN / THEN
      assertThatThrownBy(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Container has no CPU count set")
          .hasMessageContaining("withCpuCount");
    }
  }

  @Nested
  @DisplayName("combined validation")
  class CombinedValidation {

    @Test
    @DisplayName("should validate both memory and CPU")
    void shouldValidateBothMemoryAndCpu() {
      // GIVEN
      when(hostConfig.getMemory()).thenReturn(512L * 1024 * 1024);
      when(hostConfig.getCpuCount()).thenReturn(4L);

      final ConfigureContainer annotation = createAnnotation("512M", 4, 0, "");

      // WHEN / THEN
      assertThatCode(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should fail if any validation fails")
    void shouldFailIfAnyValidationFails() {
      // GIVEN
      when(hostConfig.getMemory()).thenReturn(512L * 1024 * 1024);
      when(hostConfig.getCpuCount()).thenReturn(2L); // Insufficient

      final ConfigureContainer annotation = createAnnotation("512M", 4, 0, "");

      // WHEN / THEN
      assertThatThrownBy(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cpus");
    }
  }

  @Nested
  @DisplayName("edge cases")
  class EdgeCases {

    @Test
    @DisplayName("should handle empty annotations list")
    void shouldHandleEmptyAnnotationsList() {
      // WHEN / THEN
      assertThatCode(() -> ContainerConfigHandler.process(containerField, testInstance, List.of()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should handle annotations without ConfigureContainer")
    void shouldHandleAnnotationsWithoutConfigureContainer() {
      // GIVEN
      final Annotation otherAnnotation = mock(Annotation.class);

      // WHEN / THEN
      assertThatCode(
              () ->
                  ContainerConfigHandler.process(
                      containerField, testInstance, List.of(otherAnnotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should handle empty configuration")
    void shouldHandleEmptyConfiguration() {
      // GIVEN
      final ConfigureContainer annotation = createAnnotation("", 0, 0, "");

      // WHEN / THEN
      assertThatCode(
              () ->
                  ContainerConfigHandler.process(containerField, testInstance, List.of(annotation)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject null field")
    void shouldRejectNullField() {
      // WHEN / THEN
      assertThatThrownBy(() -> ContainerConfigHandler.process(null, testInstance, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("field must not be null");
    }

    @Test
    @DisplayName("should reject null testInstance")
    void shouldRejectNullTestInstance() {
      // WHEN / THEN
      assertThatThrownBy(() -> ContainerConfigHandler.process(containerField, null, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("testInstance must not be null");
    }

    @Test
    @DisplayName("should reject null annotations")
    void shouldRejectNullAnnotations() {
      // WHEN / THEN
      assertThatThrownBy(() -> ContainerConfigHandler.process(containerField, testInstance, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("annotations must not be null");
    }
  }

  // Test helper class
  static class TestClass {
    @SuppressWarnings("unused")
    GenericContainer<?> container;
  }

  // Helper to create ConfigureContainer annotation
  private ConfigureContainer createAnnotation(
      final String memory, final int cpus, final int cpuShares, final String diskSize) {
    return new ConfigureContainer() {
      @Override
      public Class<? extends Annotation> annotationType() {
        return ConfigureContainer.class;
      }

      @Override
      public String memory() {
        return memory;
      }

      @Override
      public int cpus() {
        return cpus;
      }

      @Override
      public int cpuShares() {
        return cpuShares;
      }

      @Override
      public String diskSize() {
        return diskSize;
      }

      @Override
      public String memorySwap() {
        return "";
      }
    };
  }
}
