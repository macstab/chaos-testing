/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ValidationUtils}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ValidationUtils")
class ValidationUtilsTest {

  @Nested
  @DisplayName("validatePackageName")
  class ValidatePackageName {

    @Test
    @DisplayName("should accept valid package name")
    void shouldAcceptValidPackageName() {
      assertThatCode(() -> ValidationUtils.validatePackageName("curl")).doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validatePackageName("tcpdump"))
          .doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validatePackageName("netcat-openbsd"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject null package name")
    void shouldRejectNullPackageName() {
      assertThatThrownBy(() -> ValidationUtils.validatePackageName(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("Package name must not be null");
    }

    @Test
    @DisplayName("should reject empty package name")
    void shouldRejectEmptyPackageName() {
      assertThatThrownBy(() -> ValidationUtils.validatePackageName(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Package name must not be empty");
    }

    @Test
    @DisplayName("should reject whitespace-only package name")
    void shouldRejectWhitespaceOnlyPackageName() {
      assertThatThrownBy(() -> ValidationUtils.validatePackageName("   "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Package name must not be empty");
    }
  }

  @Nested
  @DisplayName("validateMemoryFormat")
  class ValidateMemoryFormat {

    @Test
    @DisplayName("should accept valid memory formats")
    void shouldAcceptValidMemoryFormats() {
      assertThatCode(() -> ValidationUtils.validateMemoryFormat("512M")).doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateMemoryFormat("1G")).doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateMemoryFormat("2048K"))
          .doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateMemoryFormat("100M")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject null memory")
    void shouldRejectNullMemory() {
      assertThatThrownBy(() -> ValidationUtils.validateMemoryFormat(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("Memory must not be null");
    }

    @Test
    @DisplayName("should reject invalid memory format - no unit")
    void shouldRejectInvalidMemoryFormatNoUnit() {
      assertThatThrownBy(() -> ValidationUtils.validateMemoryFormat("512"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid memory format")
          .hasMessageContaining("512");
    }

    @Test
    @DisplayName("should reject invalid memory format - wrong unit")
    void shouldRejectInvalidMemoryFormatWrongUnit() {
      assertThatThrownBy(() -> ValidationUtils.validateMemoryFormat("512T"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid memory format")
          .hasMessageContaining("512T");
    }

    @Test
    @DisplayName("should reject invalid memory format - lowercase unit")
    void shouldRejectInvalidMemoryFormatLowercaseUnit() {
      assertThatThrownBy(() -> ValidationUtils.validateMemoryFormat("512m"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid memory format");
    }
  }

  @Nested
  @DisplayName("validateDiskSizeFormat")
  class ValidateDiskSizeFormat {

    @Test
    @DisplayName("should accept valid disk size formats")
    void shouldAcceptValidDiskSizeFormats() {
      assertThatCode(() -> ValidationUtils.validateDiskSizeFormat("10G"))
          .doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateDiskSizeFormat("1T")).doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateDiskSizeFormat("500G"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject null disk size")
    void shouldRejectNullDiskSize() {
      assertThatThrownBy(() -> ValidationUtils.validateDiskSizeFormat(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("Disk size must not be null");
    }

    @Test
    @DisplayName("should reject invalid disk size format - no unit")
    void shouldRejectInvalidDiskSizeFormatNoUnit() {
      assertThatThrownBy(() -> ValidationUtils.validateDiskSizeFormat("10"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid disk size format")
          .hasMessageContaining("10");
    }

    @Test
    @DisplayName("should reject invalid disk size format - wrong unit")
    void shouldRejectInvalidDiskSizeFormatWrongUnit() {
      assertThatThrownBy(() -> ValidationUtils.validateDiskSizeFormat("10M"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid disk size format")
          .hasMessageContaining("10M");
    }
  }

  @Nested
  @DisplayName("validateCpuCount")
  class ValidateCpuCount {

    @Test
    @DisplayName("should accept valid CPU counts")
    void shouldAcceptValidCpuCounts() {
      assertThatCode(() -> ValidationUtils.validateCpuCount(1)).doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateCpuCount(2)).doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateCpuCount(16)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject zero CPU count")
    void shouldRejectZeroCpuCount() {
      assertThatThrownBy(() -> ValidationUtils.validateCpuCount(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CPU count must be positive")
          .hasMessageContaining("0");
    }

    @Test
    @DisplayName("should reject negative CPU count")
    void shouldRejectNegativeCpuCount() {
      assertThatThrownBy(() -> ValidationUtils.validateCpuCount(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CPU count must be positive")
          .hasMessageContaining("-1");
    }
  }

  @Nested
  @DisplayName("validateCpuShares")
  class ValidateCpuShares {

    @Test
    @DisplayName("should accept valid CPU shares")
    void shouldAcceptValidCpuShares() {
      assertThatCode(() -> ValidationUtils.validateCpuShares(1)).doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateCpuShares(512)).doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateCpuShares(1024)).doesNotThrowAnyException();

      assertThatCode(() -> ValidationUtils.validateCpuShares(262144)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should reject zero CPU shares")
    void shouldRejectZeroCpuShares() {
      assertThatThrownBy(() -> ValidationUtils.validateCpuShares(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CPU shares must be positive")
          .hasMessageContaining("0");
    }

    @Test
    @DisplayName("should reject negative CPU shares")
    void shouldRejectNegativeCpuShares() {
      assertThatThrownBy(() -> ValidationUtils.validateCpuShares(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CPU shares must be positive")
          .hasMessageContaining("-1");
    }

    @Test
    @DisplayName("should reject CPU shares above maximum")
    void shouldRejectCpuSharesAboveMaximum() {
      assertThatThrownBy(() -> ValidationUtils.validateCpuShares(262145))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CPU shares must be <= 262144")
          .hasMessageContaining("262145");
    }
  }
}
