/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.toxiproxy;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for ToxiproxyInstaller.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("ToxiproxyInstaller")
class ToxiproxyInstallerTest {

  @Container
  private static final GenericContainer<?> UBUNTU =
      new GenericContainer<>("ubuntu:22.04").withCommand("sleep", "infinity");

  @Container
  private static final GenericContainer<?> ALPINE =
      new GenericContainer<>("alpine:3.19").withCommand("sleep", "infinity");

  private final ToxiproxyInstaller installer = new ToxiproxyInstaller();

  @Nested
  @DisplayName("install()")
  class InstallTests {

    @Test
    @DisplayName("should install Toxiproxy on Ubuntu")
    void shouldInstallOnUbuntu() {
      // When
      installer.install(UBUNTU);

      // Then - should be installed and executable
      assertThatNoException()
          .isThrownBy(() -> UBUNTU.execInContainer("toxiproxy-server", "-version"));
    }

    @Test
    @DisplayName("should install Toxiproxy on Alpine")
    void shouldInstallOnAlpine() {
      installer.install(ALPINE);

      assertThatNoException()
          .isThrownBy(() -> ALPINE.execInContainer("toxiproxy-server", "-version"));
    }

    @Test
    @DisplayName("should be idempotent (installing twice)")
    void shouldBeIdempotent() {
      installer.install(UBUNTU);
      installer.install(UBUNTU); // Install again

      // Should not fail
      assertThatNoException()
          .isThrownBy(() -> UBUNTU.execInContainer("toxiproxy-server", "-version"));
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> installer.install(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("isInstalled()")
  class IsInstalledTests {

    @Test
    @DisplayName("should return false when not installed")
    void shouldReturnFalseWhenNotInstalled() {
      // Fresh container, Toxiproxy not installed
      assertThat(installer.isInstalled(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should return true after installation")
    void shouldReturnTrueAfterInstallation() {
      installer.install(UBUNTU);

      assertThat(installer.isInstalled(UBUNTU)).isTrue();
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> installer.isInstalled(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Installation Scenarios")
  class InstallationScenarios {

    @Test
    @DisplayName("should install on different platforms")
    void shouldInstallOnDifferentPlatforms() {
      // Ubuntu
      installer.install(UBUNTU);
      assertThat(installer.isInstalled(UBUNTU)).isTrue();

      // Alpine
      installer.install(ALPINE);
      assertThat(installer.isInstalled(ALPINE)).isTrue();
    }

    @Test
    @DisplayName("should support reinstallation after removal")
    void shouldSupportReinstallation() {
      installer.install(UBUNTU);
      assertThat(installer.isInstalled(UBUNTU)).isTrue();

      // Remove binary
      UBUNTU.execInContainer("rm", "-f", "/usr/local/bin/toxiproxy-server");
      assertThat(installer.isInstalled(UBUNTU)).isFalse();

      // Reinstall
      installer.install(UBUNTU);
      assertThat(installer.isInstalled(UBUNTU)).isTrue();
    }
  }
}
