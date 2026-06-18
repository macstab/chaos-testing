/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.platform.linux.AlpineLinuxPlatform;
import com.macstab.chaos.core.platform.linux.DebianLinuxPlatform;
import com.macstab.chaos.core.platform.linux.RhelLinuxPlatform;
import com.macstab.chaos.core.platform.linux.UbuntuLinuxPlatform;

/**
 * Unit tests for {@link PlatformDetector}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("PlatformDetector")
@ExtendWith(MockitoExtension.class)
class PlatformDetectorTest {

  @Mock private GenericContainer<?> container;

  @Nested
  @DisplayName("detect via /etc/os-release")
  class DetectViaOsRelease {

    @ParameterizedTest
    @MethodSource("osReleaseProvider")
    @DisplayName("should detect platform from /etc/os-release content")
    void shouldDetectPlatformFromOsRelease(
        final String osRelease, final Class<? extends Platform> expected) throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult result = mockExecResult(0, osRelease, "");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(expected);
    }

    static Stream<Arguments> osReleaseProvider() {
      return Stream.of(
          Arguments.of("ID=debian\nNAME=\"Debian GNU/Linux\"\n", DebianLinuxPlatform.class),
          Arguments.of("ID=\"debian\"\nNAME=\"Debian GNU/Linux\"\n", DebianLinuxPlatform.class),
          Arguments.of("ID=alpine\nNAME=\"Alpine Linux\"\n", AlpineLinuxPlatform.class),
          Arguments.of("ID=ubuntu\nNAME=\"Ubuntu\"\n", UbuntuLinuxPlatform.class),
          Arguments.of("ID=\"ubuntu\"\nNAME=\"Ubuntu\"\n", UbuntuLinuxPlatform.class),
          Arguments.of("ID=\"rhel\"\nNAME=\"Red Hat Enterprise Linux\"\n", RhelLinuxPlatform.class),
          Arguments.of("ID=\"centos\"\nNAME=\"CentOS Linux\"\n", RhelLinuxPlatform.class),
          Arguments.of("ID=\"rocky\"\nNAME=\"Rocky Linux\"\n", RhelLinuxPlatform.class),
          Arguments.of("ID=\"almalinux\"\nNAME=\"AlmaLinux\"\n", RhelLinuxPlatform.class));
    }

    @Test
    @DisplayName("should handle case-insensitive matching")
    void shouldHandleCaseInsensitiveMatching() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult result = mockExecResult(0, "ID=DEBIAN\n", "");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(DebianLinuxPlatform.class);
    }

    @Test
    @DisplayName("should handle multiline /etc/os-release")
    void shouldHandleMultilineOsRelease() throws Exception {
      // GIVEN
      final String osRelease =
          "NAME=\"Debian GNU/Linux\"\n"
              + "VERSION_ID=\"12\"\n"
              + "VERSION=\"12 (bookworm)\"\n"
              + "ID=debian\n"
              + "HOME_URL=\"https://www.debian.org/\"\n";

      when(container.isRunning()).thenReturn(true);
      final ExecResult result = mockExecResult(0, osRelease, "");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(result);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(DebianLinuxPlatform.class);
    }

    @Test
    @DisplayName("should fallback when /etc/os-release missing")
    void shouldFallbackWhenOsReleaseMissing() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult osReleaseResult = mockExecResult(1, "", "No such file");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      final ExecResult aptResult = mockExecResult(0, "/usr/bin/apt-get", "");
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(DebianLinuxPlatform.class);
    }

    @Test
    @DisplayName("should fallback when /etc/os-release unrecognized")
    void shouldFallbackWhenOsReleaseUnrecognized() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult osReleaseResult = mockExecResult(0, "ID=gentoo\n", "");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      final ExecResult aptResult = mockExecResult(1, "", "");
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      final ExecResult apkResult = mockExecResult(0, "/sbin/apk", "");
      when(container.execInContainer("which", "apk")).thenReturn(apkResult);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(AlpineLinuxPlatform.class);
    }
  }

  @Nested
  @DisplayName("detect via package manager")
  class DetectViaPackageManager {

    @Test
    @DisplayName("should detect Debian via apt-get")
    void shouldDetectDebianViaAptGet() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult osReleaseResult = mockExecResult(1, "", "No such file");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      final ExecResult aptResult = mockExecResult(0, "/usr/bin/apt-get", "");
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(DebianLinuxPlatform.class);
    }

    @Test
    @DisplayName("should detect Ubuntu via apt-get + os-release check")
    void shouldDetectUbuntuViaAptGetPlusOsRelease() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);

      // First os-release call fails, then apt-get succeeds, then os-release succeeds
      final ExecResult osReleaseFail = mockExecResult(1, "", "No such file");
      final ExecResult osReleaseSuccess = mockExecResult(0, "ID=ubuntu\n", "");
      when(container.execInContainer("cat", "/etc/os-release"))
          .thenReturn(osReleaseFail)
          .thenReturn(osReleaseSuccess);

      final ExecResult aptResult = mockExecResult(0, "/usr/bin/apt-get", "");
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(UbuntuLinuxPlatform.class);
    }

    @Test
    @DisplayName("should detect Alpine via apk")
    void shouldDetectAlpineViaApk() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult osReleaseResult = mockExecResult(1, "", "No such file");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      final ExecResult aptResult = mockExecResult(1, "", "");
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      final ExecResult apkResult = mockExecResult(0, "/sbin/apk", "");
      when(container.execInContainer("which", "apk")).thenReturn(apkResult);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(AlpineLinuxPlatform.class);
    }

    @Test
    @DisplayName("should detect RHEL via dnf")
    void shouldDetectRhelViaDnf() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult osReleaseResult = mockExecResult(1, "", "No such file");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      final ExecResult aptResult = mockExecResult(1, "", "");
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      final ExecResult apkResult = mockExecResult(1, "", "");
      when(container.execInContainer("which", "apk")).thenReturn(apkResult);

      final ExecResult dnfResult = mockExecResult(0, "/usr/bin/dnf", "");
      when(container.execInContainer("which", "dnf")).thenReturn(dnfResult);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(RhelLinuxPlatform.class);
    }

    @Test
    @DisplayName("should detect RHEL via yum (fallback)")
    void shouldDetectRhelViaYum() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult osReleaseResult = mockExecResult(1, "", "No such file");
      when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osReleaseResult);

      final ExecResult aptResult = mockExecResult(1, "", "");
      when(container.execInContainer("which", "apt-get")).thenReturn(aptResult);

      final ExecResult apkResult = mockExecResult(1, "", "");
      when(container.execInContainer("which", "apk")).thenReturn(apkResult);

      final ExecResult dnfResult = mockExecResult(1, "", "");
      when(container.execInContainer("which", "dnf")).thenReturn(dnfResult);

      final ExecResult yumResult = mockExecResult(0, "/usr/bin/yum", "");
      when(container.execInContainer("which", "yum")).thenReturn(yumResult);

      // WHEN
      final Platform platform = PlatformDetector.detect(container);

      // THEN
      assertThat(platform).isInstanceOf(RhelLinuxPlatform.class);
    }

    @Test
    @DisplayName("should throw UnsupportedPlatformException when no manager found")
    void shouldThrowUnsupportedPlatformExceptionWhenNoManagerFound() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult failResult = mockExecResult(1, "", "");
      when(container.execInContainer(any(String.class), any(String.class))).thenReturn(failResult);

      // WHEN / THEN
      assertThatThrownBy(() -> PlatformDetector.detect(container))
          .isInstanceOf(UnsupportedPlatformException.class)
          .hasMessageContaining("Could not detect platform");
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      // WHEN / THEN
      assertThatThrownBy(() -> PlatformDetector.detect(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container must not be null");
    }

    @Test
    @DisplayName("should reject non-running container")
    void shouldRejectNonRunningContainer() {
      // GIVEN
      when(container.isRunning()).thenReturn(false);

      // WHEN / THEN
      assertThatThrownBy(() -> PlatformDetector.detect(container))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Container is not running");
    }
  }

  // Helper method to create mocked ExecResult
  private static ExecResult mockExecResult(
      final int exitCode, final String stdout, final String stderr) {
    final ExecResult result = mock(ExecResult.class);
    lenient().when(result.getExitCode()).thenReturn(exitCode);
    lenient().when(result.getStdout()).thenReturn(stdout);
    lenient().when(result.getStderr()).thenReturn(stderr);
    return result;
  }
}
