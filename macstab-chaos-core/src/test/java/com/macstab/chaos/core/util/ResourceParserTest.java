/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResourceParser}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ResourceParser")
class ResourceParserTest {

  @Nested
  @DisplayName("Memory Parsing")
  class MemoryParsing {

    @Test
    @DisplayName("should parse megabytes")
    void shouldParseMegabytes() {
      assertThat(ResourceParser.parseMemoryBytes("512M")).isEqualTo(512L * 1024 * 1024);
      assertThat(ResourceParser.parseMemoryBytes("1M")).isEqualTo(1024L * 1024);
      assertThat(ResourceParser.parseMemoryBytes("2048M")).isEqualTo(2048L * 1024 * 1024);
    }

    @Test
    @DisplayName("should parse gigabytes")
    void shouldParseGigabytes() {
      assertThat(ResourceParser.parseMemoryBytes("1G")).isEqualTo(1024L * 1024 * 1024);
      assertThat(ResourceParser.parseMemoryBytes("2G")).isEqualTo(2L * 1024 * 1024 * 1024);
      assertThat(ResourceParser.parseMemoryBytes("10G")).isEqualTo(10L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("should parse kilobytes")
    void shouldParseKilobytes() {
      assertThat(ResourceParser.parseMemoryBytes("512K")).isEqualTo(512L * 1024);
      assertThat(ResourceParser.parseMemoryBytes("2048K")).isEqualTo(2048L * 1024);
    }

    @Test
    @DisplayName("should be case-insensitive")
    void shouldBeCaseInsensitive() {
      assertThat(ResourceParser.parseMemoryBytes("512m")).isEqualTo(512L * 1024 * 1024);
      assertThat(ResourceParser.parseMemoryBytes("1g")).isEqualTo(1024L * 1024 * 1024);
      assertThat(ResourceParser.parseMemoryBytes("2048k")).isEqualTo(2048L * 1024);
    }

    @Test
    @DisplayName("should reject invalid suffix")
    void shouldRejectInvalidSuffix() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("512MB"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid memory format '512MB'")
          .hasMessageContaining("expected '512M', '1G', or '2048K'");

      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("1GB"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid memory format '1GB'");

      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("512"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid memory format '512'");
    }

    @Test
    @DisplayName("should reject negative values")
    void shouldRejectNegativeValues() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("-512M"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid memory format");
    }

    @Test
    @DisplayName("should reject zero")
    void shouldRejectZero() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("0M"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Memory must be positive");
    }

    @Test
    @DisplayName("should reject null or blank")
    void shouldRejectNullOrBlank() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or blank");

      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or blank");

      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("   "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or blank");
    }

    @Test
    @DisplayName("should handle whitespace trimming")
    void shouldHandleWhitespaceTrimming() {
      assertThat(ResourceParser.parseMemoryBytes("  512M  ")).isEqualTo(512L * 1024 * 1024);
    }

    @Test
    @DisplayName("should handle maximum values")
    void shouldHandleMaximumValues() {
      assertThat(ResourceParser.parseMemoryBytes("1024G")).isEqualTo(1024L * 1024 * 1024 * 1024);
    }
  }

  @Nested
  @DisplayName("CPU Parsing")
  class CpuParsing {

    @Test
    @DisplayName("should parse integer CPUs")
    void shouldParseIntegerCpus() {
      assertThat(ResourceParser.parseCpuNanoCpus("1")).isEqualTo(1_000_000_000L);
      assertThat(ResourceParser.parseCpuNanoCpus("2")).isEqualTo(2_000_000_000L);
      assertThat(ResourceParser.parseCpuNanoCpus("4")).isEqualTo(4_000_000_000L);
    }

    @Test
    @DisplayName("should parse decimal CPUs")
    void shouldParseDecimalCpus() {
      assertThat(ResourceParser.parseCpuNanoCpus("0.5")).isEqualTo(500_000_000L);
      assertThat(ResourceParser.parseCpuNanoCpus("1.5")).isEqualTo(1_500_000_000L);
      assertThat(ResourceParser.parseCpuNanoCpus("2.5")).isEqualTo(2_500_000_000L);
    }

    @Test
    @DisplayName("should parse trailing zero decimals")
    void shouldParseTrailingZeroDecimals() {
      assertThat(ResourceParser.parseCpuNanoCpus("2.0")).isEqualTo(2_000_000_000L);
      assertThat(ResourceParser.parseCpuNanoCpus("4.0")).isEqualTo(4_000_000_000L);
    }

    @Test
    @DisplayName("should parse very small CPUs")
    void shouldParseVerySmallCpus() {
      assertThat(ResourceParser.parseCpuNanoCpus("0.1")).isEqualTo(100_000_000L);
      assertThat(ResourceParser.parseCpuNanoCpus("0.01")).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("should reject invalid format")
    void shouldRejectInvalidFormat() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("2.5.5"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid CPU count '2.5.5'")
          .hasMessageContaining("expected decimal like '2' or '0.5'");

      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("2a"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid CPU count '2a'");

      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("2."))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid CPU count '2.'");
    }

    @Test
    @DisplayName("should reject negative values")
    void shouldRejectNegativeValues() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("-2"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid CPU count");

      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("-0.5"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid CPU count");
    }

    @Test
    @DisplayName("should reject zero")
    void shouldRejectZero() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("0"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CPU count must be positive");

      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("0.0"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CPU count must be positive");
    }

    @Test
    @DisplayName("should reject null or blank")
    void shouldRejectNullOrBlank() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or blank");

      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or blank");
    }

    @Test
    @DisplayName("should handle whitespace trimming")
    void shouldHandleWhitespaceTrimming() {
      assertThat(ResourceParser.parseCpuNanoCpus("  2.5  ")).isEqualTo(2_500_000_000L);
    }
  }

  @Nested
  @DisplayName("Disk Size Parsing")
  class DiskSizeParsing {

    @Test
    @DisplayName("should parse gigabytes")
    void shouldParseGigabytes() {
      assertThat(ResourceParser.parseDiskSizeOption("10G")).isEqualTo("size=10G");
      assertThat(ResourceParser.parseDiskSizeOption("5G")).isEqualTo("size=5G");
      assertThat(ResourceParser.parseDiskSizeOption("100G")).isEqualTo("size=100G");
    }

    @Test
    @DisplayName("should be case-insensitive")
    void shouldBeCaseInsensitive() {
      assertThat(ResourceParser.parseDiskSizeOption("10g")).isEqualTo("size=10G");
      assertThat(ResourceParser.parseDiskSizeOption("5G")).isEqualTo("size=5G");
    }

    @Test
    @DisplayName("should reject invalid suffix")
    void shouldRejectInvalidSuffix() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("10GB"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid disk size '10GB'")
          .hasMessageContaining("expected gigabytes like '10G' or '5G'");

      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("512M"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid disk size '512M'");

      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("10"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid disk size '10'");
    }

    @Test
    @DisplayName("should reject negative values")
    void shouldRejectNegativeValues() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("-10G"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid disk size");
    }

    @Test
    @DisplayName("should reject zero")
    void shouldRejectZero() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("0G"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Disk size must be positive");
    }

    @Test
    @DisplayName("should reject null or blank")
    void shouldRejectNullOrBlank() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or blank");

      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or blank");
    }

    @Test
    @DisplayName("should handle whitespace trimming")
    void shouldHandleWhitespaceTrimming() {
      assertThat(ResourceParser.parseDiskSizeOption("  10G  ")).isEqualTo("size=10G");
    }
  }
}
