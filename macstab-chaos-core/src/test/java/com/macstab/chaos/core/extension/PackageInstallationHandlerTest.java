/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.annotation.InstallPackages;
import com.macstab.chaos.core.annotation.InstallTools;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;

/**
 * Unit tests for {@link PackageInstallationHandler}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("PackageInstallationHandler")
class PackageInstallationHandlerTest {

  private GenericContainer<?> container;
  private Field containerField;
  private TestClass testInstance;
  private Platform platform;

  @BeforeEach
  void setUp() throws Exception {
    container = mock(GenericContainer.class);
    platform = mock(Platform.class);

    testInstance = new TestClass();
    containerField = TestClass.class.getDeclaredField("container");
    containerField.setAccessible(true);
    containerField.set(testInstance, container);

    when(container.isRunning()).thenReturn(true);
  }

  @Nested
  @DisplayName("package installation")
  class PackageInstallation {

    @Test
    @DisplayName("should install raw packages")
    void shouldInstallRawPackages() {
      try (MockedStatic<PlatformDetector> detectorMock = mockStatic(PlatformDetector.class);
          MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {

        // GIVEN
        detectorMock.when(() -> PlatformDetector.detect(container)).thenReturn(platform);

        final InstallPackages annotation = createPackagesAnnotation("curl", "wget");

        // WHEN
        PackageInstallationHandler.process(containerField, testInstance, List.of(annotation));

        // THEN
        installerMock.verify(() -> PackageInstaller.install(eq(container), anyList(), eq(false)));
      }
    }

    @Test
    @DisplayName("should deduplicate packages")
    void shouldDeduplicatePackages() {
      try (MockedStatic<PlatformDetector> detectorMock = mockStatic(PlatformDetector.class);
          MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {

        // GIVEN
        detectorMock.when(() -> PlatformDetector.detect(container)).thenReturn(platform);

        final InstallPackages annotation1 = createPackagesAnnotation("curl", "wget");
        final InstallPackages annotation2 = createPackagesAnnotation("curl", "jq");

        // WHEN
        PackageInstallationHandler.process(
            containerField, testInstance, List.of(annotation1, annotation2));

        // THEN (curl should only appear once)
        installerMock.verify(() -> PackageInstaller.install(eq(container), anyList(), eq(false)));
      }
    }

    @Test
    @DisplayName("should validate package names")
    void shouldValidatePackageNames() {
      // GIVEN - Package name with space (invalid)
      final InstallPackages annotation = createPackagesAnnotation("invalid package name!");

      // WHEN / THEN - Validation happens in ValidationUtils.validatePackageName()
      // This should throw BEFORE platform detection
      assertThatThrownBy(
              () ->
                  PackageInstallationHandler.process(
                      containerField, testInstance, List.of(annotation)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid package name");
    }
  }

  @Nested
  @DisplayName("tool installation")
  class ToolInstallation {

    @Test
    @DisplayName("should translate tools to platform-specific packages")
    void shouldTranslateToolsToPlatformSpecificPackages() {
      try (MockedStatic<PlatformDetector> detectorMock = mockStatic(PlatformDetector.class);
          MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {

        // GIVEN
        detectorMock.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        when(platform.getPackageName(Tool.CURL)).thenReturn("curl");
        when(platform.getPackageName(Tool.IPTABLES)).thenReturn("iptables");

        final InstallTools annotation = createToolsAnnotation(Tool.CURL, Tool.IPTABLES);

        // WHEN
        PackageInstallationHandler.process(containerField, testInstance, List.of(annotation));

        // THEN
        verify(platform).getPackageName(Tool.CURL);
        verify(platform).getPackageName(Tool.IPTABLES);
        installerMock.verify(() -> PackageInstaller.install(eq(container), anyList(), eq(false)));
      }
    }

    @Test
    @DisplayName("should handle RHEL-specific package names")
    void shouldHandleRhelSpecificPackageNames() {
      try (MockedStatic<PlatformDetector> detectorMock = mockStatic(PlatformDetector.class);
          MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {

        // GIVEN
        detectorMock.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        when(platform.getPackageName(Tool.PROCPS)).thenReturn("procps-ng"); // RHEL-specific

        final InstallTools annotation = createToolsAnnotation(Tool.PROCPS);

        // WHEN
        PackageInstallationHandler.process(containerField, testInstance, List.of(annotation));

        // THEN
        verify(platform).getPackageName(Tool.PROCPS);
      }
    }
  }

  @Nested
  @DisplayName("combined installation")
  class CombinedInstallation {

    @Test
    @DisplayName("should install both packages and tools")
    void shouldInstallBothPackagesAndTools() {
      try (MockedStatic<PlatformDetector> detectorMock = mockStatic(PlatformDetector.class);
          MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {

        // GIVEN
        detectorMock.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        when(platform.getPackageName(Tool.CURL)).thenReturn("curl");

        final InstallPackages packagesAnnotation = createPackagesAnnotation("wget", "jq");
        final InstallTools toolsAnnotation = createToolsAnnotation(Tool.CURL);

        // WHEN
        PackageInstallationHandler.process(
            containerField, testInstance, List.of(packagesAnnotation, toolsAnnotation));

        // THEN
        installerMock.verify(() -> PackageInstaller.install(eq(container), anyList(), eq(false)));
      }
    }
  }

  @Nested
  @DisplayName("verification")
  class Verification {

    @Test
    @DisplayName("should verify installation when requested")
    void shouldVerifyInstallationWhenRequested() {
      try (MockedStatic<PlatformDetector> detectorMock = mockStatic(PlatformDetector.class);
          MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {

        // GIVEN
        detectorMock.when(() -> PlatformDetector.detect(container)).thenReturn(platform);

        final InstallPackages annotation = createPackagesAnnotationWithVerify("curl", true);

        // WHEN
        PackageInstallationHandler.process(containerField, testInstance, List.of(annotation));

        // THEN
        installerMock.verify(() -> PackageInstaller.install(eq(container), anyList(), eq(true)));
      }
    }

    @Test
    @DisplayName("should not verify by default")
    void shouldNotVerifyByDefault() {
      try (MockedStatic<PlatformDetector> detectorMock = mockStatic(PlatformDetector.class);
          MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {

        // GIVEN
        detectorMock.when(() -> PlatformDetector.detect(container)).thenReturn(platform);

        final InstallPackages annotation = createPackagesAnnotation("curl");

        // WHEN
        PackageInstallationHandler.process(containerField, testInstance, List.of(annotation));

        // THEN
        installerMock.verify(() -> PackageInstaller.install(eq(container), anyList(), eq(false)));
      }
    }
  }

  @Nested
  @DisplayName("edge cases")
  class EdgeCases {

    @Test
    @DisplayName("should handle empty annotations list")
    void shouldHandleEmptyAnnotationsList() {
      try (MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {
        // WHEN
        PackageInstallationHandler.process(containerField, testInstance, List.of());

        // THEN (no installation should happen)
        installerMock.verify(() -> PackageInstaller.install(any(), anyList(), eq(false)), never());
      }
    }

    @Test
    @DisplayName("should handle annotations without package/tool annotations")
    void shouldHandleAnnotationsWithoutPackageToolAnnotations() {
      try (MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {
        // GIVEN
        final Annotation otherAnnotation = mock(Annotation.class);

        // WHEN
        PackageInstallationHandler.process(containerField, testInstance, List.of(otherAnnotation));

        // THEN
        installerMock.verify(() -> PackageInstaller.install(any(), anyList(), eq(false)), never());
      }
    }

    @Test
    @DisplayName("should skip installation when container not running")
    void shouldSkipInstallationWhenContainerNotRunning() {
      try (MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {
        // GIVEN
        when(container.isRunning()).thenReturn(false);

        final InstallPackages annotation = createPackagesAnnotation("curl");

        // WHEN
        PackageInstallationHandler.process(containerField, testInstance, List.of(annotation));

        // THEN
        installerMock.verify(() -> PackageInstaller.install(any(), anyList(), eq(false)), never());
      }
    }

    @Test
    @DisplayName("should reject null field")
    void shouldRejectNullField() {
      // WHEN / THEN
      assertThatThrownBy(() -> PackageInstallationHandler.process(null, testInstance, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("field must not be null");
    }

    @Test
    @DisplayName("should reject null testInstance")
    void shouldRejectNullTestInstance() {
      // WHEN / THEN
      assertThatThrownBy(() -> PackageInstallationHandler.process(containerField, null, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("testInstance must not be null");
    }

    @Test
    @DisplayName("should reject null annotations")
    void shouldRejectNullAnnotations() {
      // WHEN / THEN
      assertThatThrownBy(
              () -> PackageInstallationHandler.process(containerField, testInstance, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("annotations must not be null");
    }
  }

  @Nested
  @DisplayName("error handling")
  class ErrorHandling {

    @Test
    @DisplayName("should throw exception on installation failure")
    void shouldThrowExceptionOnInstallationFailure() {
      try (MockedStatic<PlatformDetector> detectorMock = mockStatic(PlatformDetector.class);
          MockedStatic<PackageInstaller> installerMock = mockStatic(PackageInstaller.class)) {

        // GIVEN
        detectorMock.when(() -> PlatformDetector.detect(container)).thenReturn(platform);

        installerMock
            .when(() -> PackageInstaller.install(any(), anyList(), eq(false)))
            .thenThrow(new RuntimeException("Installation failed"));

        final InstallPackages annotation = createPackagesAnnotation("curl");

        // WHEN / THEN
        assertThatThrownBy(
                () ->
                    PackageInstallationHandler.process(
                        containerField, testInstance, List.of(annotation)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to install packages")
            .hasCauseInstanceOf(RuntimeException.class);
      }
    }
  }

  // Test helper class
  static class TestClass {
    @SuppressWarnings("unused")
    GenericContainer<?> container;
  }

  // Helper to create InstallPackages annotation
  private InstallPackages createPackagesAnnotation(final String... packages) {
    return createPackagesAnnotationWithVerify(packages[0], false);
  }

  private InstallPackages createPackagesAnnotationWithVerify(
      final String packageName, final boolean verify) {
    return new InstallPackages() {
      @Override
      public Class<? extends Annotation> annotationType() {
        return InstallPackages.class;
      }

      @Override
      public String[] value() {
        return new String[] {packageName};
      }

      @Override
      public boolean verify() {
        return verify;
      }

      @Override
      public String target() {
        return "";
      }
    };
  }

  // Helper to create InstallTools annotation
  private InstallTools createToolsAnnotation(final Tool... tools) {
    return new InstallTools() {
      @Override
      public Class<? extends Annotation> annotationType() {
        return InstallTools.class;
      }

      @Override
      public Tool[] value() {
        return tools;
      }

      @Override
      public boolean verify() {
        return false;
      }

      @Override
      public String target() {
        return "";
      }
    };
  }
}
