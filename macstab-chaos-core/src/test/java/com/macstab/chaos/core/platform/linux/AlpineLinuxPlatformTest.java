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
 * Unit tests for {@link AlpineLinuxPlatform}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("AlpineLinuxPlatform")
class AlpineLinuxPlatformTest {

  private Platform platform;

  @BeforeEach
  void setUp() {
    platform = new AlpineLinuxPlatform();
  }

  @Nested
  @DisplayName("platform metadata")
  class PlatformMetadata {

    @Test
    @DisplayName("should return alpine distribution name")
    void shouldReturnAlpineDistributionName() {
      assertThat(platform.getDistribution()).isEqualTo("alpine");
    }

    @Test
    @DisplayName("should return Linux platform type")
    void shouldReturnLinuxPlatformType() {
      assertThat(platform.getType()).isEqualTo(PlatformType.LINUX);
    }
  }

  @Nested
  @DisplayName("tool translation")
  class ToolTranslation {

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
    @DisplayName("should translate PROCPS to procps")
    void shouldTranslateProcpsToProcps() {
      assertThat(platform.getPackageName(Tool.PROCPS)).isEqualTo("procps");
    }

    @Test
    @DisplayName("should translate IPROUTE to iproute2")
    void shouldTranslateIprouteToIproute2() {
      assertThat(platform.getPackageName(Tool.IPROUTE)).isEqualTo("iproute2");
    }

    @Test
    @DisplayName("should translate PYTHON to python3")
    void shouldTranslatePythonToPython3() {
      assertThat(platform.getPackageName(Tool.PYTHON)).isEqualTo("python3");
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
    @DisplayName("should translate CURL binary to curl")
    void shouldTranslateCurlBinaryToCurl() {
      assertThat(platform.getBinaryName(Tool.CURL)).isEqualTo("curl");
    }

    @Test
    @DisplayName("should translate IPTABLES binary to iptables")
    void shouldTranslateIptablesBinaryToIptables() {
      assertThat(platform.getBinaryName(Tool.IPTABLES)).isEqualTo("iptables");
    }

    @Test
    @DisplayName("should translate PROCPS binary to ps")
    void shouldTranslateProcpsBinaryToPs() {
      assertThat(platform.getBinaryName(Tool.PROCPS)).isEqualTo("ps");
    }

    @Test
    @DisplayName("should translate IPROUTE binary to tc")
    void shouldTranslateIprouteBinaryToIp() {
      assertThat(platform.getBinaryName(Tool.IPROUTE)).isEqualTo("ip");
    }

    @Test
    @DisplayName("should translate PYTHON binary to python3")
    void shouldTranslatePythonBinaryToPython3() {
      assertThat(platform.getBinaryName(Tool.PYTHON)).isEqualTo("python3");
    }
  }
}
