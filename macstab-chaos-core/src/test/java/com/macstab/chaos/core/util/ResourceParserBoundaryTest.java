/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Boundary and extreme value tests for {@link ResourceParser}.
 *
 * <p>Tests cover edge cases, boundary conditions, overflow scenarios, and unusual inputs.
 *
 * @author Christian Schnapka / Macstab GmbH
 */
@DisplayName("ResourceParser - Boundary Cases")
class ResourceParserBoundaryTest {

  @Nested
  @DisplayName("Memory Boundary Cases")
  class MemoryBoundaryTest {

    @Test
    @DisplayName("Should parse minimum valid memory (1K)")
    void shouldParseMinimumMemory() {
      assertThat(ResourceParser.parseMemoryBytes("1K")).isEqualTo(1024L);
    }

    @Test
    @DisplayName("Should parse maximum reasonable memory (1024G)")
    void shouldParseMaximumMemory() {
      assertThat(ResourceParser.parseMemoryBytes("1024G")).isEqualTo(1024L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("Should reject memory exceeding maximum (1025G)")
    void shouldRejectExcessiveMemory() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("1025G"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exceeds maximum");
    }

    @Test
    @DisplayName("Should parse zero as valid (0M)")
    void shouldParseZero() {
      assertThat(ResourceParser.parseMemoryBytes("0M")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should handle very large numbers within limit")
    void shouldHandleLargeNumbers() {
      assertThat(ResourceParser.parseMemoryBytes("1023G")).isEqualTo(1023L * 1024 * 1024 * 1024);
      assertThat(ResourceParser.parseMemoryBytes("1048576K")).isEqualTo(1024L * 1024 * 1024);
    }

    @Test
    @DisplayName("Should reject negative values")
    void shouldRejectNegative() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("-512M"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject fractional bytes (0.5K)")
    void shouldRejectFractionalBytes() {
      // Fractional values only allowed for CPUs, not memory
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("0.5K"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject invalid units")
    void shouldRejectInvalidUnits() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("512T"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("512B"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should handle leading + sign")
    void shouldHandleLeadingPlus() {
      assertThat(ResourceParser.parseMemoryBytes("+512M")).isEqualTo(512L * 1024 * 1024);
    }

    @Test
    @DisplayName("Should reject whitespace in input")
    void shouldRejectWhitespace() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes(" 512M"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("512 M"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes("512M "))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("CPU Boundary Cases")
  class CpuBoundaryTest {

    @Test
    @DisplayName("Should parse minimum valid CPU (0.01)")
    void shouldParseMinimumCpu() {
      assertThat(ResourceParser.parseCpuNanoCpus("0.01")).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("Should parse maximum reasonable CPUs (128)")
    void shouldParseMaximumCpu() {
      assertThat(ResourceParser.parseCpuNanoCpus("128")).isEqualTo(128L * 1_000_000_000L);
    }

    @Test
    @DisplayName("Should reject CPUs exceeding maximum (129)")
    void shouldRejectExcessiveCpus() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("129"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exceeds maximum");
    }

    @Test
    @DisplayName("Should parse zero as valid (0)")
    void shouldParseZeroCpu() {
      assertThat(ResourceParser.parseCpuNanoCpus("0")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should parse zero with decimal (0.0)")
    void shouldParseZeroDecimal() {
      assertThat(ResourceParser.parseCpuNanoCpus("0.0")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should handle very precise decimals (0.001)")
    void shouldHandlePreciseDecimals() {
      assertThat(ResourceParser.parseCpuNanoCpus("0.001")).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("Should handle many decimal places (0.123456789)")
    void shouldHandleManyDecimals() {
      // 0.123456789 CPUs = 123456789 nano-CPUs
      assertThat(ResourceParser.parseCpuNanoCpus("0.123456789")).isEqualTo(123_456_789L);
    }

    @Test
    @DisplayName("Should reject negative CPUs")
    void shouldRejectNegative() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("-1"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("-0.5"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should handle leading + sign")
    void shouldHandleLeadingPlus() {
      assertThat(ResourceParser.parseCpuNanoCpus("+2")).isEqualTo(2_000_000_000L);
      assertThat(ResourceParser.parseCpuNanoCpus("+0.5")).isEqualTo(500_000_000L);
    }

    @Test
    @DisplayName("Should reject invalid formats")
    void shouldRejectInvalidFormats() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("1.2.3"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("abc"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("1CPU"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject whitespace in input")
    void shouldRejectWhitespace() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus(" 2"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("2 "))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus("1 .5"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Disk Size Boundary Cases")
  class DiskBoundaryTest {

    @Test
    @DisplayName("Should parse minimum valid disk (1G)")
    void shouldParseMinimumDisk() {
      assertThat(ResourceParser.parseDiskSizeOption("1G")).isEqualTo("--tmpfs-size=1G");
    }

    @Test
    @DisplayName("Should parse maximum reasonable disk (1024G)")
    void shouldParseMaximumDisk() {
      assertThat(ResourceParser.parseDiskSizeOption("1024G")).isEqualTo("--tmpfs-size=1024G");
    }

    @Test
    @DisplayName("Should reject disk exceeding maximum (1025G)")
    void shouldRejectExcessiveDisk() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("1025G"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exceeds maximum");
    }

    @Test
    @DisplayName("Should be case-insensitive (lower)")
    void shouldBeCaseInsensitiveLower() {
      assertThat(ResourceParser.parseDiskSizeOption("10g")).isEqualTo("--tmpfs-size=10G");
    }

    @Test
    @DisplayName("Should be case-insensitive (upper)")
    void shouldBeCaseInsensitiveUpper() {
      assertThat(ResourceParser.parseDiskSizeOption("10G")).isEqualTo("--tmpfs-size=10G");
    }

    @Test
    @DisplayName("Should normalize output to uppercase G")
    void shouldNormalizeToUppercase() {
      assertThat(ResourceParser.parseDiskSizeOption("512g")).isEqualTo("--tmpfs-size=512G");
    }

    @Test
    @DisplayName("Should reject non-G units")
    void shouldRejectNonGUnits() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("512M"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("1024K"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("1T"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject fractional values")
    void shouldRejectFractional() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("1.5G"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject negative values")
    void shouldRejectNegative() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("-10G"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should handle leading + sign")
    void shouldHandleLeadingPlus() {
      assertThat(ResourceParser.parseDiskSizeOption("+10G")).isEqualTo("--tmpfs-size=10G");
    }

    @Test
    @DisplayName("Should reject whitespace in input")
    void shouldRejectWhitespace() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption(" 10G"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("10 G"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption("10G "))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Null and Empty Input")
  class NullEmptyTest {

    @Test
    @DisplayName("Should reject null memory input")
    void shouldRejectNullMemory() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject empty memory input")
    void shouldRejectEmptyMemory() {
      assertThatThrownBy(() -> ResourceParser.parseMemoryBytes(""))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject null CPU input")
    void shouldRejectNullCpu() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject empty CPU input")
    void shouldRejectEmptyCpu() {
      assertThatThrownBy(() -> ResourceParser.parseCpuNanoCpus(""))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject null disk input")
    void shouldRejectNullDisk() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject empty disk input")
    void shouldRejectEmptyDisk() {
      assertThatThrownBy(() -> ResourceParser.parseDiskSizeOption(""))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
