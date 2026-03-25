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
 * Unit tests for {@link UbuntuLinuxPlatform}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("UbuntuLinuxPlatform")
class UbuntuLinuxPlatformTest {

  private Platform platform;

  @BeforeEach
  void setUp() {
    platform = new UbuntuLinuxPlatform();
  }

  @Nested
  @DisplayName("platform metadata")
  class PlatformMetadata {

    @Test
    @DisplayName("should return ubuntu distribution name")
    void shouldReturnUbuntuDistributionName() {
      assertThat(platform.getDistribution()).isEqualTo("ubuntu");
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
      // Ubuntu uses procps like Debian, NOT procps-ng
      assertThat(platform.getPackageName(Tool.PROCPS)).isEqualTo("procps");
    }

    @Test
    @DisplayName("should translate IPROUTE to iproute2")
    void shouldTranslateIprouteToIproute2() {
      assertThat(platform.getPackageName(Tool.IPROUTE)).isEqualTo("iproute2");
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
    @DisplayName("should translate PROCPS binary to ps")
    void shouldTranslateProcpsBinaryToPs() {
      assertThat(platform.getBinaryName(Tool.PROCPS)).isEqualTo("ps");
    }

    @Test
    @DisplayName("should translate IPROUTE binary to tc")
    void shouldTranslateIprouteBinaryToIp() {
      assertThat(platform.getBinaryName(Tool.IPROUTE)).isEqualTo("ip");
    }
  }
}
