/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform.linux;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.*;

import com.macstab.chaos.core.platform.*;

/** Tests for AbstractLinuxPlatform tool override mechanism. */
@DisplayName("AbstractLinuxPlatform - Tool Overrides")
class ToolOverrideTest {

  static class PlatformWithOverrides extends AbstractLinuxPlatform {
    @Override
    public String getDistribution() {
      return "custom-linux";
    }

    @Override
    protected Map<Tool, ToolMapping> getToolOverrides() {
      return Map.of(
          Tool.PROCPS, new ToolMapping("procps-ng", "ps"),
          Tool.PYTHON, new ToolMapping("python", "python"));
    }
  }

  static class PlatformWithoutOverrides extends AbstractLinuxPlatform {
    @Override
    public String getDistribution() {
      return "default-linux";
    }
  }

  @Test
  @DisplayName("Should use override for PROCPS")
  void shouldUseOverrideForProcps() {
    Platform platform = new PlatformWithOverrides();

    String packageName = platform.getPackageName(Tool.PROCPS);
    String binaryName = platform.getBinaryName(Tool.PROCPS);

    assertThat(packageName).isEqualTo("procps-ng");
    assertThat(binaryName).isEqualTo("ps");
  }

  @Test
  @DisplayName("Should use override for PYTHON")
  void shouldUseOverrideForPython() {
    Platform platform = new PlatformWithOverrides();

    String packageName = platform.getPackageName(Tool.PYTHON);
    String binaryName = platform.getBinaryName(Tool.PYTHON);

    assertThat(packageName).isEqualTo("python");
    assertThat(binaryName).isEqualTo("python");
  }

  @Test
  @DisplayName("Should use default for non-overridden tools")
  void shouldUseDefaultForNonOverridden() {
    Platform platform = new PlatformWithOverrides();

    // CURL not overridden, should use default
    String curlPackage = platform.getPackageName(Tool.CURL);
    String curlBinary = platform.getBinaryName(Tool.CURL);

    assertThat(curlPackage).isEqualTo("curl");
    assertThat(curlBinary).isEqualTo("curl");
  }

  @Test
  @DisplayName("Platform without overrides should use all defaults")
  void platformWithoutOverrides_shouldUseDefaults() {
    Platform platform = new PlatformWithoutOverrides();

    assertThat(platform.getPackageName(Tool.PROCPS)).isEqualTo("procps");
    assertThat(platform.getBinaryName(Tool.PROCPS)).isEqualTo("ps");

    assertThat(platform.getPackageName(Tool.PYTHON)).isEqualTo("python3");
    assertThat(platform.getBinaryName(Tool.PYTHON)).isEqualTo("python3");
  }

  @Test
  @DisplayName("Override should take precedence over default")
  void override_shouldTakePrecedenceOverDefault() {
    Platform withOverrides = new PlatformWithOverrides();
    Platform withoutOverrides = new PlatformWithoutOverrides();

    // PYTHON override differs from default
    String overridePackage = withOverrides.getPackageName(Tool.PYTHON);
    String defaultPackage = withoutOverrides.getPackageName(Tool.PYTHON);

    assertThat(overridePackage).isEqualTo("python");
    assertThat(defaultPackage).isEqualTo("python3");
    assertThat(overridePackage).isNotEqualTo(defaultPackage);
  }

  @Test
  @DisplayName("Should handle tool with null binary name")
  void shouldHandleToolWithNullBinaryName() {
    Platform platform = new PlatformWithOverrides();

    // CA_CERTIFICATES has null binary in defaults
    String packageName = platform.getPackageName(Tool.CA_CERTIFICATES);
    String binaryName = platform.getBinaryName(Tool.CA_CERTIFICATES);

    assertThat(packageName).isEqualTo("ca-certificates");
    // When binary is null, should return package name as fallback
    assertThat(binaryName).isEqualTo("ca-certificates");
  }
}
