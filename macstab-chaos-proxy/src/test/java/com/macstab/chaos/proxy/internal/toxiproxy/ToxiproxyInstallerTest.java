/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.toxiproxy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.macstab.chaos.proxy.internal.ContainerContext;

/**
 * Tests for {@link ToxiproxyInstaller}.
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
    void shouldInstallOnUbuntu() throws Exception {
      // GIVEN
      final ContainerContext ctx = ContainerContext.of(UBUNTU);

      // WHEN
      installer.install(ctx);

      // THEN
      assertThat(UBUNTU.execInContainer("which", "toxiproxy-server").getExitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("should install Toxiproxy on Alpine")
    void shouldInstallOnAlpine() throws Exception {
      // GIVEN
      final ContainerContext ctx = ContainerContext.of(ALPINE);

      // WHEN
      installer.install(ctx);

      // THEN
      assertThat(ALPINE.execInContainer("which", "toxiproxy-server").getExitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("should be idempotent — installing twice does not fail")
    void shouldBeIdempotent() throws Exception {
      // GIVEN
      final ContainerContext ctx = ContainerContext.of(UBUNTU);

      // WHEN
      installer.install(ctx);
      installer.install(ctx); // second call — should skip silently

      // THEN
      assertThat(UBUNTU.execInContainer("which", "toxiproxy-server").getExitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("should throw NullPointerException when ctx is null")
    void shouldThrowNpe_whenCtxIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> installer.install(null))
          .withMessage("ctx must not be null");
    }
  }

  @Nested
  @DisplayName("Binary verification")
  class BinaryVerificationTests {

    @Test
    @DisplayName("should detect missing binary before installation")
    void shouldDetectMissingBinary_beforeInstall() throws Exception {
      // Remove binary to ensure clean state
      UBUNTU.execInContainer("rm", "-f", "/usr/local/bin/toxiproxy-server");
      assertThat(UBUNTU.execInContainer("which", "toxiproxy-server").getExitCode()).isNotEqualTo(0);
    }

    @Test
    @DisplayName("should detect present binary after installation")
    void shouldDetectPresentBinary_afterInstall() throws Exception {
      final ContainerContext ctx = ContainerContext.of(UBUNTU);
      installer.install(ctx);
      assertThat(UBUNTU.execInContainer("which", "toxiproxy-server").getExitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("should support reinstallation after binary removal")
    void shouldSupportReinstallation() throws Exception {
      // GIVEN — install, remove, reinstall
      final ContainerContext ctx = ContainerContext.of(UBUNTU);
      installer.install(ctx);
      UBUNTU.execInContainer("rm", "-f", "/usr/local/bin/toxiproxy-server");

      // WHEN
      installer.install(ctx);

      // THEN
      assertThat(UBUNTU.execInContainer("which", "toxiproxy-server").getExitCode()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Cross-platform")
  class CrossPlatformTests {

    @Test
    @DisplayName("should install on both Debian-based and Alpine containers")
    void shouldInstallOnBothPlatforms() throws Exception {
      installer.install(ContainerContext.of(UBUNTU));
      installer.install(ContainerContext.of(ALPINE));

      assertThat(UBUNTU.execInContainer("which", "toxiproxy-server").getExitCode()).isEqualTo(0);
      assertThat(ALPINE.execInContainer("which", "toxiproxy-server").getExitCode()).isEqualTo(0);
    }
  }
}
