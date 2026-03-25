/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link PackageManager}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("PackageManager")
class PackageManagerTest {

  private GenericContainer<?> container;

  @BeforeEach
  void setUp() {
    container = mock(GenericContainer.class);
  }

  @Nested
  @DisplayName("APT package manager")
  class AptPackageManager {

    @Test
    @DisplayName("should detect APT availability")
    void shouldDetectAptAvailability() throws Exception {
      // GIVEN
      final ExecResult result = mockExecResult(0, "/usr/bin/apt-get", "");
      when(container.execInContainer("which", "apt-get")).thenReturn(result);

      // WHEN
      final boolean available = PackageManager.APT.isAvailable(container);

      // THEN
      assertThat(available).isTrue();
    }

    @Test
    @DisplayName("should detect APT unavailability")
    void shouldDetectAptUnavailability() throws Exception {
      // GIVEN
      final ExecResult result = mockExecResult(1, "", "not found");
      when(container.execInContainer("which", "apt-get")).thenReturn(result);

      // WHEN
      final boolean available = PackageManager.APT.isAvailable(container);

      // THEN
      assertThat(available).isFalse();
    }

    @Test
    @DisplayName("should install packages via APT")
    void shouldInstallPackagesViaApt() throws Exception {
      // GIVEN
      final ExecResult updateResult = mockExecResult(0, "Reading package lists...", "");
      when(container.execInContainer("apt-get", "update")).thenReturn(updateResult);

      final ExecResult installResult = mockExecResult(0, "Installing curl...", "");
      when(container.execInContainer(any(String[].class))).thenReturn(installResult);

      // WHEN
      PackageManager.APT.install(container, "curl");

      // THEN
      verify(container).execInContainer("apt-get", "update");
    }

    @Test
    @DisplayName("should fail on update failure")
    void shouldFailOnUpdateFailure() throws Exception {
      // GIVEN
      final ExecResult updateResult = mockExecResult(1, "", "Failed to update");
      when(container.execInContainer("apt-get", "update")).thenReturn(updateResult);

      // WHEN / THEN
      assertThatThrownBy(() -> PackageManager.APT.install(container, "curl"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("apt-get update");
    }

    @Test
    @DisplayName("should return correct command")
    void shouldReturnCorrectCommand() {
      assertThat(PackageManager.APT.getCommand()).isEqualTo("apt-get");
    }

    @Test
    @DisplayName("should support Debian and Ubuntu")
    void shouldSupportDebianAndUbuntu() {
      assertThat(PackageManager.APT.getDistributions()).contains("debian", "ubuntu");
    }
  }

  @Nested
  @DisplayName("APK package manager")
  class ApkPackageManager {

    @Test
    @DisplayName("should detect APK availability")
    void shouldDetectApkAvailability() throws Exception {
      // GIVEN
      final ExecResult result = mockExecResult(0, "/sbin/apk", "");
      when(container.execInContainer("which", "apk")).thenReturn(result);

      // WHEN
      final boolean available = PackageManager.APK.isAvailable(container);

      // THEN
      assertThat(available).isTrue();
    }

    @Test
    @DisplayName("should install packages via APK")
    void shouldInstallPackagesViaApk() throws Exception {
      // GIVEN
      final ExecResult updateResult = mockExecResult(0, "fetch...", "");
      when(container.execInContainer("apk", "update")).thenReturn(updateResult);

      final ExecResult installResult = mockExecResult(0, "Installing curl...", "");
      when(container.execInContainer(any(String[].class))).thenReturn(installResult);

      // WHEN
      PackageManager.APK.install(container, "curl");

      // THEN
      verify(container).execInContainer("apk", "update");
    }

    @Test
    @DisplayName("should return correct command")
    void shouldReturnCorrectCommand() {
      assertThat(PackageManager.APK.getCommand()).isEqualTo("apk");
    }

    @Test
    @DisplayName("should support Alpine")
    void shouldSupportAlpine() {
      assertThat(PackageManager.APK.getDistributions()).contains("alpine");
    }
  }

  @Nested
  @DisplayName("DNF package manager")
  class DnfPackageManager {

    @Test
    @DisplayName("should detect DNF availability")
    void shouldDetectDnfAvailability() throws Exception {
      // GIVEN
      final ExecResult result = mockExecResult(0, "/usr/bin/dnf", "");
      when(container.execInContainer("which", "dnf")).thenReturn(result);

      // WHEN
      final boolean available = PackageManager.DNF.isAvailable(container);

      // THEN
      assertThat(available).isTrue();
    }

    @Test
    @DisplayName("should install packages via DNF")
    void shouldInstallPackagesViaDnf() throws Exception {
      // GIVEN
      final ExecResult installResult = mockExecResult(0, "Installing curl...", "");
      when(container.execInContainer(any(String[].class))).thenReturn(installResult);

      // WHEN
      PackageManager.DNF.install(container, "curl");

      // THEN (DNF doesn't require update first)
      verify(container).execInContainer(any(String[].class));
    }

    @Test
    @DisplayName("should support Fedora and RHEL")
    void shouldSupportFedoraAndRhel() {
      assertThat(PackageManager.DNF.getDistributions()).contains("fedora", "rhel", "centos");
    }
  }

  @Nested
  @DisplayName("YUM package manager")
  class YumPackageManager {

    @Test
    @DisplayName("should detect YUM availability")
    void shouldDetectYumAvailability() throws Exception {
      // GIVEN
      final ExecResult result = mockExecResult(0, "/usr/bin/yum", "");
      when(container.execInContainer("which", "yum")).thenReturn(result);

      // WHEN
      final boolean available = PackageManager.YUM.isAvailable(container);

      // THEN
      assertThat(available).isTrue();
    }

    @Test
    @DisplayName("should install packages via YUM")
    void shouldInstallPackagesViaYum() throws Exception {
      // GIVEN
      final ExecResult installResult = mockExecResult(0, "Installing curl...", "");
      when(container.execInContainer(any(String[].class))).thenReturn(installResult);

      // WHEN
      PackageManager.YUM.install(container, "curl");

      // THEN
      verify(container).execInContainer(any(String[].class));
    }

    @Test
    @DisplayName("should support CentOS and RHEL")
    void shouldSupportCentosAndRhel() {
      assertThat(PackageManager.YUM.getDistributions()).contains("centos", "rhel");
    }
  }

  @Nested
  @DisplayName("PACMAN package manager")
  class PacmanPackageManager {

    @Test
    @DisplayName("should detect PACMAN availability")
    void shouldDetectPacmanAvailability() throws Exception {
      // GIVEN
      final ExecResult result = mockExecResult(0, "/usr/bin/pacman", "");
      when(container.execInContainer("which", "pacman")).thenReturn(result);

      // WHEN
      final boolean available = PackageManager.PACMAN.isAvailable(container);

      // THEN
      assertThat(available).isTrue();
    }

    @Test
    @DisplayName("should install packages via PACMAN")
    void shouldInstallPackagesViaPacman() throws Exception {
      // GIVEN
      final ExecResult updateResult = mockExecResult(0, "synchronizing...", "");
      when(container.execInContainer("pacman", "-Sy")).thenReturn(updateResult);

      final ExecResult installResult = mockExecResult(0, "Installing curl...", "");
      when(container.execInContainer(any(String[].class))).thenReturn(installResult);

      // WHEN
      PackageManager.PACMAN.install(container, "curl");

      // THEN
      verify(container).execInContainer("pacman", "-Sy");
    }

    @Test
    @DisplayName("should support Arch")
    void shouldSupportArch() {
      assertThat(PackageManager.PACMAN.getDistributions()).contains("arch");
    }
  }

  @Nested
  @DisplayName("ZYPPER package manager")
  class ZypperPackageManager {

    @Test
    @DisplayName("should detect ZYPPER availability")
    void shouldDetectZypperAvailability() throws Exception {
      // GIVEN
      final ExecResult result = mockExecResult(0, "/usr/bin/zypper", "");
      when(container.execInContainer("which", "zypper")).thenReturn(result);

      // WHEN
      final boolean available = PackageManager.ZYPPER.isAvailable(container);

      // THEN
      assertThat(available).isTrue();
    }

    @Test
    @DisplayName("should install packages via ZYPPER")
    void shouldInstallPackagesViaZypper() throws Exception {
      // GIVEN
      final ExecResult refreshResult = mockExecResult(0, "Refreshing...", "");
      when(container.execInContainer("zypper", "refresh")).thenReturn(refreshResult);

      final ExecResult installResult = mockExecResult(0, "Installing curl...", "");
      when(container.execInContainer(any(String[].class))).thenReturn(installResult);

      // WHEN
      PackageManager.ZYPPER.install(container, "curl");

      // THEN
      verify(container).execInContainer("zypper", "refresh");
    }

    @Test
    @DisplayName("should support openSUSE")
    void shouldSupportOpenSuse() {
      assertThat(PackageManager.ZYPPER.getDistributions()).contains("opensuse", "sles");
    }
  }

  @Nested
  @DisplayName("package manager selection")
  class PackageManagerSelection {

    @Test
    @DisplayName("should detect available package manager")
    void shouldDetectAvailablePackageManager() throws Exception {
      // GIVEN
      final ExecResult notFoundResult = mockExecResult(1, "", "");
      final ExecResult foundResult = mockExecResult(0, "/sbin/apk", "");
      when(container.execInContainer("which", "apt-get")).thenReturn(notFoundResult);
      when(container.execInContainer("which", "apk")).thenReturn(foundResult);

      // WHEN
      final PackageManager manager = PackageManager.detect(container);

      // THEN
      assertThat(manager).isEqualTo(PackageManager.APK);
    }

    @Test
    @DisplayName("should throw exception when no package manager found")
    void shouldThrowExceptionWhenNoPackageManagerFound() throws Exception {
      // GIVEN
      final ExecResult notFoundResult = mockExecResult(1, "", "not found");
      when(container.execInContainer(any(String.class), any(String.class)))
          .thenReturn(notFoundResult);

      // WHEN / THEN
      assertThatThrownBy(() -> PackageManager.detect(container))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Could not detect package manager");
    }
  }

  @Nested
  @DisplayName("enum properties")
  class EnumProperties {

    @Test
    @DisplayName("should have 6 package managers")
    void shouldHaveSixPackageManagers() {
      assertThat(PackageManager.values()).hasSize(6);
    }

    @Test
    @DisplayName("should contain all expected managers")
    void shouldContainAllExpectedManagers() {
      assertThat(PackageManager.values())
          .contains(
              PackageManager.APT,
              PackageManager.APK,
              PackageManager.DNF,
              PackageManager.YUM,
              PackageManager.PACMAN,
              PackageManager.ZYPPER);
    }
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
