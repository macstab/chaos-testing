/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("Errno")
class ErrnoTest {

  @Nested
  @DisplayName("wireForm")
  class WireForm {

    @ParameterizedTest
    @EnumSource(Errno.class)
    @DisplayName("is non-null and non-blank for every value")
    void nonBlank(final Errno errno) {
      assertThat(errno.wireForm()).isNotNull().isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(Errno.class)
    @DisplayName("is uppercase (POSIX convention)")
    void uppercase(final Errno errno) {
      assertThat(errno.wireForm()).isEqualTo(errno.wireForm().toUpperCase(Locale.ROOT));
    }

    @ParameterizedTest
    @EnumSource(Errno.class)
    @DisplayName("starts with 'E' (POSIX errno prefix)")
    void posixPrefix(final Errno errno) {
      assertThat(errno.wireForm()).startsWith("E");
    }

    @Test
    @DisplayName("is unique across all values")
    void uniqueAcrossValues() {
      final Map<String, Long> counts =
          Arrays.stream(Errno.values())
              .map(Errno::wireForm)
              .collect(Collectors.groupingBy(form -> form, Collectors.counting()));
      assertThat(counts).allSatisfy((wire, count) -> assertThat(count).isEqualTo(1L));
    }

    @Test
    @DisplayName("matches the libchaos-net grammar exactly")
    void exactGrammarMapping() {
      assertThat(Errno.ECONNREFUSED.wireForm()).isEqualTo("ECONNREFUSED");
      assertThat(Errno.ETIMEDOUT.wireForm()).isEqualTo("ETIMEDOUT");
      assertThat(Errno.ECONNRESET.wireForm()).isEqualTo("ECONNRESET");
      assertThat(Errno.EHOSTUNREACH.wireForm()).isEqualTo("EHOSTUNREACH");
      assertThat(Errno.ENETUNREACH.wireForm()).isEqualTo("ENETUNREACH");
      assertThat(Errno.EADDRINUSE.wireForm()).isEqualTo("EADDRINUSE");
      assertThat(Errno.EADDRNOTAVAIL.wireForm()).isEqualTo("EADDRNOTAVAIL");
      assertThat(Errno.EPIPE.wireForm()).isEqualTo("EPIPE");
      assertThat(Errno.EMFILE.wireForm()).isEqualTo("EMFILE");
      assertThat(Errno.ENFILE.wireForm()).isEqualTo("ENFILE");
      assertThat(Errno.EAGAIN.wireForm()).isEqualTo("EAGAIN");
    }
  }

  @Nested
  @DisplayName("enum surface")
  class Surface {

    @Test
    @DisplayName("declares exactly nineteen errnos matching the libchaos-net palette")
    void valueCount() {
      assertThat(Errno.values()).hasSize(19);
    }
  }
}
