/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Edge case tests for {@link PlatformDetector}.
 *
 * <p>Tests cover error paths, unusual inputs, fallback behavior, and boundary conditions.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("PlatformDetector - Edge Cases")
class PlatformDetectorEdgeCasesTest {

  @Nested
  @DisplayName("Input Validation")
  class InputValidationTest {

    @Test
    @DisplayName("Should throw NPE when container is null")
    void shouldThrowNpeWhenContainerNull() {
      // ACT & ASSERT
      assertThatThrownBy(() -> PlatformDetector.detect(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container must not be null");
    }

    @Test
    @DisplayName("Should throw IllegalStateException when container not running")
    void shouldThrowWhenContainerNotRunning() {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);

      // ACT & ASSERT
      assertThatThrownBy(() -> PlatformDetector.detect(container))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Container is not running");
    }
  }

  @Nested
  @DisplayName("os-release Parsing Edge Cases")
  class OsReleaseParsingTest {

    @Test
    @DisplayName("Should detect Debian with quoted ID")
    void shouldDetectDebianWithQuotedId() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(result.getStdout()).thenReturn("ID=\"debian\"\nVERSION_ID=\"12\"\n");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("debian");
    }

    @Test
    @DisplayName("Should detect Ubuntu with quoted ID")
    void shouldDetectUbuntuWithQuotedId() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(result.getStdout()).thenReturn("ID=\"ubuntu\"\nVERSION_ID=\"22.04\"\n");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("ubuntu");
    }

    @Test
    @DisplayName("Should detect RHEL with quoted ID")
    void shouldDetectRhelWithQuotedId() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(result.getStdout()).thenReturn("ID=\"rhel\"\nVERSION_ID=\"9\"\n");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("rhel");
    }

    @Test
    @DisplayName("Should detect CentOS via os-release")
    void shouldDetectCentos() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(result.getStdout()).thenReturn("ID=\"centos\"\nVERSION_ID=\"8\"\n");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("rhel");
    }

    @Test
    @DisplayName("Should detect Rocky Linux via os-release")
    void shouldDetectRocky() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(result.getStdout()).thenReturn("ID=\"rocky\"\nVERSION_ID=\"9\"\n");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("rhel");
    }

    @Test
    @DisplayName("Should detect AlmaLinux via os-release")
    void shouldDetectAlmaLinux() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(result.getStdout()).thenReturn("ID=\"almalinux\"\nVERSION_ID=\"9\"\n");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("rhel");
    }

    @Test
    @DisplayName("Should handle case-insensitive matching")
    void shouldHandleCaseInsensitive() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(result.getStdout()).thenReturn("ID=ALPINE\nVERSION_ID=3.19\n");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("alpine");
    }
  }

  @Nested
  @DisplayName("Fallback to Package Manager")
  class FallbackTest {

    @Test
    @DisplayName("Should fallback to apt-get when os-release missing")
    void shouldFallbackToAptGet() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      // os-release not found
      ExecResult osReleaseResult = mock(ExecResult.class);
      when(osReleaseResult.getExitCode()).thenReturn(1);
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      // apt-get found
      ExecResult aptResult = mock(ExecResult.class);
      when(aptResult.getExitCode()).thenReturn(0);
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      // os-release check for Ubuntu (not found)
      ExecResult osReleaseCheckResult = mock(ExecResult.class);
      when(osReleaseCheckResult.getExitCode()).thenReturn(1);
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseCheckResult);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("debian");
    }

    @Test
    @DisplayName("Should detect Ubuntu via apt-get + os-release check")
    void shouldDetectUbuntuViaAptGet() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      // os-release not found initially
      ExecResult osReleaseResult = mock(ExecResult.class);
      when(osReleaseResult.getExitCode()).thenReturn(1);

      // apt-get found
      ExecResult aptResult = mock(ExecResult.class);
      when(aptResult.getExitCode()).thenReturn(0);

      // os-release check finds Ubuntu
      ExecResult ubuntuCheck = mock(ExecResult.class);
      when(ubuntuCheck.getExitCode()).thenReturn(0);
      when(ubuntuCheck.getStdout()).thenReturn("ID=ubuntu\n");

      when(container.execInContainer("cat", "/etc/os-release"))
          .thenReturn(osReleaseResult)
          .thenReturn(ubuntuCheck);
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("ubuntu");
    }

    @Test
    @DisplayName("Should fallback to apk when os-release missing")
    void shouldFallbackToApk() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      // os-release not found
      ExecResult osReleaseResult = mock(ExecResult.class);
      when(osReleaseResult.getExitCode()).thenReturn(1);
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      // apt-get not found
      ExecResult aptResult = mock(ExecResult.class);
      when(aptResult.getExitCode()).thenReturn(1);
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      // apk found
      ExecResult apkResult = mock(ExecResult.class);
      when(apkResult.getExitCode()).thenReturn(0);
      when(container.execInContainer("which", "apk")).thenReturn(apkResult);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("alpine");
    }

    @Test
    @DisplayName("Should fallback to dnf when os-release missing")
    void shouldFallbackToDnf() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      // os-release not found
      ExecResult osReleaseResult = mock(ExecResult.class);
      when(osReleaseResult.getExitCode()).thenReturn(1);
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      // apt-get, apk not found
      ExecResult notFoundResult = mock(ExecResult.class);
      when(notFoundResult.getExitCode()).thenReturn(1);
      when(container.execInContainer("which", "apt-get")).thenReturn(notFoundResult);
      when(container.execInContainer("which", "apk")).thenReturn(notFoundResult);

      // dnf found
      ExecResult dnfResult = mock(ExecResult.class);
      when(dnfResult.getExitCode()).thenReturn(0);
      when(container.execInContainer("which", "dnf")).thenReturn(dnfResult);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("rhel");
    }

    @Test
    @DisplayName("Should fallback to yum when dnf not found")
    void shouldFallbackToYum() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      // os-release not found
      ExecResult osReleaseResult = mock(ExecResult.class);
      when(osReleaseResult.getExitCode()).thenReturn(1);
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      // apt-get, apk, dnf not found
      ExecResult notFoundResult = mock(ExecResult.class);
      when(notFoundResult.getExitCode()).thenReturn(1);
      when(container.execInContainer("which", "apt-get")).thenReturn(notFoundResult);
      when(container.execInContainer("which", "apk")).thenReturn(notFoundResult);
      when(container.execInContainer("which", "dnf")).thenReturn(notFoundResult);

      // yum found
      ExecResult yumResult = mock(ExecResult.class);
      when(yumResult.getExitCode()).thenReturn(0);
      when(container.execInContainer("which", "yum")).thenReturn(yumResult);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("rhel");
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandlingTest {

    @Test
    @DisplayName("Should throw UnsupportedPlatformException when all strategies fail")
    void shouldThrowWhenAllStrategiesFail() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      // All exec commands fail
      ExecResult failResult = mock(ExecResult.class);
      when(failResult.getExitCode()).thenReturn(1);
      when(container.execInContainer(any(String.class), any(String.class))).thenReturn(failResult);

      // ACT & ASSERT
      assertThatThrownBy(() -> PlatformDetector.detect(container))
          .isInstanceOf(UnsupportedPlatformException.class)
          .hasMessageContaining("Could not detect platform");
    }

    @Test
    @DisplayName("Should handle IOException in os-release detection")
    void shouldHandleIOExceptionInOsRelease() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      // IOException on first os-release call, then returns exit-code-1 result on second call
      ExecResult osReleaseCheckResult = mock(ExecResult.class);
      when(osReleaseCheckResult.getExitCode()).thenReturn(1);
      doThrow(new IOException("Test exception"))
          .doReturn(osReleaseCheckResult)
          .when(container).execInContainer("cat", "/etc/os-release");

      // Fallback to apt-get succeeds
      ExecResult aptResult = mock(ExecResult.class);
      when(aptResult.getExitCode()).thenReturn(0);
      when(container.execInContainer(eq("which"), eq("apt-get"))).thenReturn(aptResult);

      // ACT
      Platform platform = PlatformDetector.detect(container);

      // ASSERT
      assertThat(platform.getDistribution()).isEqualTo("debian");
    }

    @Test
    @DisplayName("Should handle InterruptedException in package manager detection")
    void shouldHandleInterruptedExceptionInPackageManager() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      // os-release fails
      ExecResult osReleaseResult = mock(ExecResult.class);
      when(osReleaseResult.getExitCode()).thenReturn(1);
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      // Package manager check throws InterruptedException
      when(container.execInContainer("which", "apt-get"))
          .thenThrow(new InterruptedException("Test exception"));

      // ACT & ASSERT
      assertThatThrownBy(() -> PlatformDetector.detect(container))
          .isInstanceOf(UnsupportedPlatformException.class);
    }

    @Test
    @DisplayName("Should handle unknown distribution in os-release")
    void shouldHandleUnknownDistribution() throws Exception {
      // ARRANGE
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      // Unknown distro in os-release
      ExecResult osReleaseResult = mock(ExecResult.class);
      when(osReleaseResult.getExitCode()).thenReturn(0);
      when(osReleaseResult.getStdout()).thenReturn("ID=unknown-distro\nVERSION_ID=1.0\n");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      // No package managers found
      ExecResult notFoundResult = mock(ExecResult.class);
      when(notFoundResult.getExitCode()).thenReturn(1);
      when(container.execInContainer(eq("which"), any(String.class))).thenReturn(notFoundResult);

      // ACT & ASSERT
      assertThatThrownBy(() -> PlatformDetector.detect(container))
          .isInstanceOf(UnsupportedPlatformException.class);
    }
  }
}
