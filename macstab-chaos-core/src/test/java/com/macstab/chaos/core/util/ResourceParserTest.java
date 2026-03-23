/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.exception.ChaosConfigurationException;

class ResourceParserTest {

  @Test
  void shouldParseKilobytes() {
    assertThat(ResourceParser.parseBytes("512K")).isEqualTo(512L * 1024L);
    assertThat(ResourceParser.parseBytes("1K")).isEqualTo(1024L);
    assertThat(ResourceParser.parseBytes("2048K")).isEqualTo(2048L * 1024L);
  }

  @Test
  void shouldParseMegabytes() {
    assertThat(ResourceParser.parseBytes("512M")).isEqualTo(512L * 1024L * 1024L);
    assertThat(ResourceParser.parseBytes("1M")).isEqualTo(1024L * 1024L);
    assertThat(ResourceParser.parseBytes("100M")).isEqualTo(100L * 1024L * 1024L);
  }

  @Test
  void shouldParseGigabytes() {
    assertThat(ResourceParser.parseBytes("1G")).isEqualTo(1024L * 1024L * 1024L);
    assertThat(ResourceParser.parseBytes("2G")).isEqualTo(2L * 1024L * 1024L * 1024L);
  }

  @Test
  void shouldParseTerabytes() {
    assertThat(ResourceParser.parseBytes("1T")).isEqualTo(1024L * 1024L * 1024L * 1024L);
  }

  @Test
  void shouldParseBytesWithoutUnit() {
    assertThat(ResourceParser.parseBytes("1024")).isEqualTo(1024L);
    assertThat(ResourceParser.parseBytes("512")).isEqualTo(512L);
  }

  @Test
  void shouldBeCaseInsensitive() {
    assertThat(ResourceParser.parseBytes("512m")).isEqualTo(512L * 1024L * 1024L);
    assertThat(ResourceParser.parseBytes("1g")).isEqualTo(1024L * 1024L * 1024L);
    assertThat(ResourceParser.parseBytes("2K")).isEqualTo(2048L);
  }

  @Test
  void shouldHandleWhitespace() {
    assertThat(ResourceParser.parseBytes(" 512M ")).isEqualTo(512L * 1024L * 1024L);
    assertThat(ResourceParser.parseBytes("  1G  ")).isEqualTo(1024L * 1024L * 1024L);
  }

  @Test
  void shouldRejectInvalidFormat() {
    assertThatThrownBy(() -> ResourceParser.parseBytes("invalid"))
        .isInstanceOf(ChaosConfigurationException.class)
        .hasMessageContaining("Invalid resource format");

    assertThatThrownBy(() -> ResourceParser.parseBytes("512MB"))
        .isInstanceOf(ChaosConfigurationException.class)
        .hasMessageContaining("Invalid resource format");

    assertThatThrownBy(() -> ResourceParser.parseBytes("M512"))
        .isInstanceOf(ChaosConfigurationException.class);
  }

  @Test
  void shouldRejectNullInput() {
    assertThatThrownBy(() -> ResourceParser.parseBytes(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("resource must not be null");
  }

  @Test
  void shouldRejectEmptyString() {
    assertThatThrownBy(() -> ResourceParser.parseBytes(""))
        .isInstanceOf(ChaosConfigurationException.class);
  }
}
