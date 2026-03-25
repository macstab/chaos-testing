/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.macstab.chaos.core.platform.linux.AlpineLinuxPlatform;
import com.macstab.chaos.core.platform.linux.DebianLinuxPlatform;
import com.macstab.chaos.core.platform.linux.UbuntuLinuxPlatform;

/**
 * Integration tests for {@link PlatformDetector} with real containers.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("PlatformDetector Integration Tests")
@Testcontainers
@Tag("integration")
class PlatformDetectorIntegrationTest {

  @Container
  private static final GenericContainer<?> debianContainer =
      new GenericContainer<>("debian:12-slim").withCommand("sleep", "infinity");

  @Container
  private static final GenericContainer<?> alpineContainer =
      new GenericContainer<>("alpine:3.19").withCommand("sleep", "infinity");

  @Container
  private static final GenericContainer<?> ubuntuContainer =
      new GenericContainer<>("ubuntu:22.04").withCommand("sleep", "infinity");

  @Test
  @DisplayName("should detect Debian platform from real container")
  void shouldDetectDebianPlatformFromRealContainer() {
    // WHEN
    final Platform platform = PlatformDetector.detect(debianContainer);

    // THEN
    assertThat(platform).isInstanceOf(DebianLinuxPlatform.class);
    assertThat(platform.getDistribution()).isEqualTo("debian");
    assertThat(platform.getType()).isEqualTo(PlatformType.LINUX);
  }

  @Test
  @DisplayName("should detect Alpine platform from real container")
  void shouldDetectAlpinePlatformFromRealContainer() {
    // WHEN
    final Platform platform = PlatformDetector.detect(alpineContainer);

    // THEN
    assertThat(platform).isInstanceOf(AlpineLinuxPlatform.class);
    assertThat(platform.getDistribution()).isEqualTo("alpine");
    assertThat(platform.getType()).isEqualTo(PlatformType.LINUX);
  }

  @Test
  @DisplayName("should detect Ubuntu platform from real container")
  void shouldDetectUbuntuPlatformFromRealContainer() {
    // WHEN
    final Platform platform = PlatformDetector.detect(ubuntuContainer);

    // THEN
    assertThat(platform).isInstanceOf(UbuntuLinuxPlatform.class);
    assertThat(platform.getDistribution()).isEqualTo("ubuntu");
    assertThat(platform.getType()).isEqualTo(PlatformType.LINUX);
  }

  @Test
  @DisplayName("should translate tools correctly on Debian")
  void shouldTranslateToolsCorrectlyOnDebian() {
    // GIVEN
    final Platform platform = PlatformDetector.detect(debianContainer);

    // WHEN / THEN
    assertThat(platform.getPackageName(Tool.PROCPS)).isEqualTo("procps");
    assertThat(platform.getPackageName(Tool.CURL)).isEqualTo("curl");
    assertThat(platform.getPackageName(Tool.IPTABLES)).isEqualTo("iptables");
  }

  @Test
  @DisplayName("should translate tools correctly on Alpine")
  void shouldTranslateToolsCorrectlyOnAlpine() {
    // GIVEN
    final Platform platform = PlatformDetector.detect(alpineContainer);

    // WHEN / THEN
    assertThat(platform.getPackageName(Tool.PROCPS)).isEqualTo("procps");
    assertThat(platform.getPackageName(Tool.PYTHON)).isEqualTo("python3");
    assertThat(platform.getPackageName(Tool.CURL)).isEqualTo("curl");
  }
}
