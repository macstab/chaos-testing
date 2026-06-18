/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("MemoryRule (unit)")
class MemoryRuleTest {

  @Nested
  @DisplayName("null checks")
  class NullChecks {
    @Test
    @DisplayName("null selector / effect rejected")
    void nulls() {
      assertThatThrownBy(() -> new MemoryRule(null, MemoryEffect.errno(MmapErrno.ENOMEM)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new MemoryRule(MemorySelector.MMAP, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("LATENCY: valid on every selector")
  class LatencyMatrix {
    @ParameterizedTest
    @MethodSource("everySelector")
    void everySelector(final MemorySelector selector) {
      new MemoryRule(selector, MemoryEffect.latency(Duration.ofMillis(10)));
    }

    static Stream<MemorySelector> everySelector() {
      return Stream.of(MemorySelector.values());
    }
  }

  @Nested
  @DisplayName("ERRNO × selector matrix")
  class ErrnoMatrix {

    @ParameterizedTest
    @MethodSource("validCombinations")
    @DisplayName("valid combinations accepted")
    void valid(final MemorySelector selector, final MmapErrno errno) {
      new MemoryRule(selector, MemoryEffect.errno(errno));
    }

    @ParameterizedTest
    @MethodSource("invalidCombinations")
    @DisplayName("invalid combinations rejected with informative message")
    void invalid(final MemorySelector selector, final MmapErrno errno) {
      assertThatThrownBy(() -> new MemoryRule(selector, MemoryEffect.errno(errno)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(errno.wireForm())
          .hasMessageContaining(selector.wireForm());
    }

    static Stream<Arguments> validCombinations() {
      return Stream.of(MemorySelector.values())
          .flatMap(s -> s.validErrnos().stream().map(e -> Arguments.of(s, e)));
    }

    static Stream<Arguments> invalidCombinations() {
      return Stream.of(MemorySelector.values())
          .flatMap(
              s ->
                  Stream.of(MmapErrno.values())
                      .filter(e -> !s.validErrnos().contains(e))
                      .map(e -> Arguments.of(s, e)));
    }

    @Test
    @DisplayName("Wildcard rejects EAGAIN (intersection-strict)")
    void wildcardStrict() {
      assertThatThrownBy(
              () -> new MemoryRule(MemorySelector.WILDCARD, MemoryEffect.errno(MmapErrno.EAGAIN)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("munmap rejects ENOMEM (only EINVAL accepted)")
    void munmapStrict() {
      assertThatThrownBy(
              () -> new MemoryRule(MemorySelector.MUNMAP, MemoryEffect.errno(MmapErrno.ENOMEM)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("mprotect rejects EAGAIN")
    void mprotectStrict() {
      assertThatThrownBy(
              () -> new MemoryRule(MemorySelector.MPROTECT, MemoryEffect.errno(MmapErrno.EAGAIN)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("static factories")
  class Factories {
    @Test
    @DisplayName("errno(selector, errno, probability)")
    void errnoFactory() {
      final MemoryRule r = MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM, 0.001);
      assertThat(r.effect()).isInstanceOf(MemoryEffect.ErrnoFault.class);
      assertThat(((MemoryEffect.ErrnoFault) r.effect()).probability()).isEqualTo(0.001);
    }

    @Test
    @DisplayName("errno(selector, errno) defaults probability to 1.0")
    void errnoFactoryDefault() {
      final MemoryRule r = MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM);
      assertThat(((MemoryEffect.ErrnoFault) r.effect()).probability()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("latency(selector, delay)")
    void latencyFactory() {
      final MemoryRule r = MemoryRule.latency(MemorySelector.MMAP_ANON, Duration.ofMillis(50));
      assertThat(r.effect()).isInstanceOf(MemoryEffect.Latency.class);
    }

    @Test
    @DisplayName("factories enforce compat matrix")
    void factoryRejectsBadCompat() {
      assertThatThrownBy(() -> MemoryRule.errno(MemorySelector.MUNMAP, MmapErrno.ENOMEM))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
