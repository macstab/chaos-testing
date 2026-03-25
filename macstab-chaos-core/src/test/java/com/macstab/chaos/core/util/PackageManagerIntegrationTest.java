/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link PackageManager} with real containers.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("PackageManager Integration Tests")
@Testcontainers
@Tag("integration")
class PackageManagerIntegrationTest {

  @Container
  private static final GenericContainer<?> debianContainer =
      new GenericContainer<>("debian:12-slim").withCommand("sleep", "infinity");

  @Container
  private static final GenericContainer<?> alpineContainer =
      new GenericContainer<>("alpine:3.19").withCommand("sleep", "infinity");

  @Test
  @DisplayName("should detect APT on Debian")
  void shouldDetectAptOnDebian() {
    // WHEN
    final PackageManager manager = PackageManager.detect(debianContainer);

    // THEN
    assertThat(manager).isEqualTo(PackageManager.APT);
  }

  @Test
  @DisplayName("should detect APK on Alpine")
  void shouldDetectApkOnAlpine() {
    // WHEN
    final PackageManager manager = PackageManager.detect(alpineContainer);

    // THEN
    assertThat(manager).isEqualTo(PackageManager.APK);
  }

  @Test
  @DisplayName("should install package on Debian")
  void shouldInstallPackageOnDebian() throws Exception {
    // WHEN / THEN
    assertThatCode(() -> PackageManager.APT.install(debianContainer, "curl"))
        .doesNotThrowAnyException();

    // Verify installation
    final var result = debianContainer.execInContainer("which", "curl");
    assertThat(result.getExitCode()).isEqualTo(0);
  }

  @Test
  @DisplayName("should install package on Alpine")
  void shouldInstallPackageOnAlpine() throws Exception {
    // WHEN / THEN
    assertThatCode(() -> PackageManager.APK.install(alpineContainer, "curl"))
        .doesNotThrowAnyException();

    // Verify installation
    final var result = alpineContainer.execInContainer("which", "curl");
    assertThat(result.getExitCode()).isEqualTo(0);
  }

  @Test
  @DisplayName("should install multiple packages on Debian")
  void shouldInstallMultiplePackagesOnDebian() throws Exception {
    // WHEN / THEN
    assertThatCode(() -> PackageManager.APT.install(debianContainer, "wget", "vim"))
        .doesNotThrowAnyException();

    // Verify installations
    assertThat(debianContainer.execInContainer("which", "wget").getExitCode()).isEqualTo(0);
    assertThat(debianContainer.execInContainer("which", "vim").getExitCode()).isEqualTo(0);
  }

  @Test
  @DisplayName("should install multiple packages on Alpine")
  void shouldInstallMultiplePackagesOnAlpine() throws Exception {
    // WHEN / THEN
    assertThatCode(() -> PackageManager.APK.install(alpineContainer, "wget", "vim"))
        .doesNotThrowAnyException();

    // Verify installations
    assertThat(alpineContainer.execInContainer("which", "wget").getExitCode()).isEqualTo(0);
    assertThat(alpineContainer.execInContainer("which", "vim").getExitCode()).isEqualTo(0);
  }
}
