/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformType;
import com.macstab.chaos.core.platform.Tool;

/**
 * Unit tests for {@link RhelLinuxPlatform}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("RhelLinuxPlatform")
class RhelLinuxPlatformTest {

  private Platform platform;

  @BeforeEach
  void setUp() {
    platform = new RhelLinuxPlatform();
  }

  @Nested
  @DisplayName("platform metadata")
  class PlatformMetadata {

    @Test
    @DisplayName("should return rhel distribution name")
    void shouldReturnRhelDistributionName() {
      assertThat(platform.getDistribution()).isEqualTo("rhel");
    }

    @Test
    @DisplayName("should return Linux platform type")
    void shouldReturnLinuxPlatformType() {
      assertThat(platform.getType()).isEqualTo(PlatformType.LINUX);
    }
  }

  @Nested
  @DisplayName("tool translation - RHEL-specific packages")
  class ToolTranslationRhelSpecific {

    @Test
    @DisplayName("should translate PROCPS to procps-ng (RHEL-specific)")
    void shouldTranslateProcpsToProcpsNg() {
      // CRITICAL: RHEL uses procps-ng, not procps
      assertThat(platform.getPackageName(Tool.PROCPS)).isEqualTo("procps-ng");
    }

    @Test
    @DisplayName("should translate IPROUTE to iproute (RHEL uses iproute not iproute2)")
    void shouldTranslateIprouteToIproute() {
      // RHEL uses package name "iproute" instead of "iproute2"
      assertThat(platform.getPackageName(Tool.IPROUTE)).isEqualTo("iproute");
    }

    @Test
    @DisplayName("should translate PYTHON to python3")
    void shouldTranslatePythonToPython3() {
      assertThat(platform.getPackageName(Tool.PYTHON)).isEqualTo("python3");
    }
  }

  @Nested
  @DisplayName("tool translation - standard packages")
  class ToolTranslationStandard {

    @Test
    @DisplayName("should translate CURL to curl")
    void shouldTranslateCurlToCurl() {
      assertThat(platform.getPackageName(Tool.CURL)).isEqualTo("curl");
    }

    @Test
    @DisplayName("should translate IPTABLES to iptables")
    void shouldTranslateIptablesToIptables() {
      assertThat(platform.getPackageName(Tool.IPTABLES)).isEqualTo("iptables");
    }

    @Test
    @DisplayName("should translate STRESS_NG to stress-ng")
    void shouldTranslateStressNgToStressNg() {
      assertThat(platform.getPackageName(Tool.STRESS_NG)).isEqualTo("stress-ng");
    }
  }

  @Nested
  @DisplayName("binary translation")
  class BinaryTranslation {

    @Test
    @DisplayName("should translate PROCPS binary to ps")
    void shouldTranslateProcpsBinaryToPs() {
      // Binary name is "ps" even though package is "procps-ng"
      assertThat(platform.getBinaryName(Tool.PROCPS)).isEqualTo("ps");
    }

    @Test
    @DisplayName("should translate IPROUTE binary to ip")
    void shouldTranslateIprouteBinaryToIp() {
      assertThat(platform.getBinaryName(Tool.IPROUTE)).isEqualTo("ip");
    }

    @Test
    @DisplayName("should translate CURL binary to curl")
    void shouldTranslateCurlBinaryToCurl() {
      assertThat(platform.getBinaryName(Tool.CURL)).isEqualTo("curl");
    }

    @Test
    @DisplayName("should translate PYTHON binary to python3")
    void shouldTranslatePythonBinaryToPython3() {
      assertThat(platform.getBinaryName(Tool.PYTHON)).isEqualTo("python3");
    }
  }
}
