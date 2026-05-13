/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.model;

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

@DisplayName("ProcessRule (unit)")
class ProcessRuleTest {

  @Nested
  @DisplayName("null checks")
  class NullChecks {
    @Test
    @DisplayName("null selector / effect rejected")
    void nulls() {
      assertThatThrownBy(() -> new ProcessRule(null, ProcessEffect.errno(ProcessErrno.EAGAIN)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new ProcessRule(ProcessSelector.FORK, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("LATENCY: valid on every selector")
  class LatencyMatrix {
    @ParameterizedTest
    @MethodSource("everySelector")
    void everySelector(final ProcessSelector selector) {
      new ProcessRule(selector, ProcessEffect.latency(Duration.ofMillis(10)));
    }

    static Stream<ProcessSelector> everySelector() {
      return Stream.of(ProcessSelector.values());
    }
  }

  @Nested
  @DisplayName("ERRNO × selector matrix (8 selectors × 12 errnos = 96 combinations)")
  class ErrnoMatrix {

    @ParameterizedTest
    @MethodSource("validCombinations")
    @DisplayName("valid (selector, errno) accepted")
    void valid(final ProcessSelector selector, final ProcessErrno errno) {
      new ProcessRule(selector, ProcessEffect.errno(errno));
    }

    @ParameterizedTest
    @MethodSource("invalidCombinations")
    @DisplayName("invalid (selector, errno) rejected with informative message")
    void invalid(final ProcessSelector selector, final ProcessErrno errno) {
      assertThatThrownBy(() -> new ProcessRule(selector, ProcessEffect.errno(errno)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(errno.wireForm())
          .hasMessageContaining(selector.wireForm());
    }

    static Stream<Arguments> validCombinations() {
      return Stream.of(ProcessSelector.values())
          .flatMap(s -> s.validErrnos().stream().map(e -> Arguments.of(s, e)));
    }

    static Stream<Arguments> invalidCombinations() {
      return Stream.of(ProcessSelector.values())
          .flatMap(
              s ->
                  Stream.of(ProcessErrno.values())
                      .filter(e -> !s.validErrnos().contains(e))
                      .map(e -> Arguments.of(s, e)));
    }

    @Test
    @DisplayName("wildcard accepts the full union — policy departure from memory module")
    void wildcardAcceptsUnion() {
      new ProcessRule(ProcessSelector.WILDCARD, ProcessEffect.errno(ProcessErrno.EAGAIN));
      new ProcessRule(ProcessSelector.WILDCARD, ProcessEffect.errno(ProcessErrno.EINTR));
      new ProcessRule(ProcessSelector.WILDCARD, ProcessEffect.errno(ProcessErrno.ESRCH));
    }

    @Test
    @DisplayName("fork rejects EINVAL (only EAGAIN / ENOMEM accepted)")
    void forkStrict() {
      assertThatThrownBy(
              () -> new ProcessRule(ProcessSelector.FORK, ProcessEffect.errno(ProcessErrno.EINVAL)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("execve rejects EAGAIN (not in 8-errno set)")
    void execStrict() {
      assertThatThrownBy(
              () ->
                  new ProcessRule(ProcessSelector.EXECVE, ProcessEffect.errno(ProcessErrno.EAGAIN)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("waitpid rejects EAGAIN")
    void waitpidStrict() {
      assertThatThrownBy(
              () ->
                  new ProcessRule(
                      ProcessSelector.WAITPID, ProcessEffect.errno(ProcessErrno.EAGAIN)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("FAIL_AFTER × selector matrix (same compat as ERRNO)")
  class FailAfterMatrix {
    @Test
    @DisplayName("valid: pthread_create:FAIL_AFTER:EAGAIN,128")
    void validPthreadFailAfter() {
      new ProcessRule(
          ProcessSelector.PTHREAD_CREATE, ProcessEffect.failAfter(ProcessErrno.EAGAIN, 128L));
    }

    @Test
    @DisplayName("invalid: fork:FAIL_AFTER:EINVAL (errno mismatch)")
    void invalidForkFailAfter() {
      assertThatThrownBy(
              () ->
                  new ProcessRule(
                      ProcessSelector.FORK, ProcessEffect.failAfter(ProcessErrno.EINVAL, 10L)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FAIL_AFTER on wildcard with union errno accepted")
    void wildcardFailAfter() {
      new ProcessRule(ProcessSelector.WILDCARD, ProcessEffect.failAfter(ProcessErrno.EAGAIN, 5L));
    }
  }

  @Nested
  @DisplayName("static factories")
  class Factories {
    @Test
    @DisplayName("errno factory builds ErrnoFault")
    void errno() {
      final ProcessRule r =
          ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN, 0.5);
      assertThat(r.effect()).isInstanceOf(ProcessEffect.ErrnoFault.class);
    }

    @Test
    @DisplayName("errno factory defaults probability to 1.0")
    void errnoDefault() {
      final ProcessRule r = ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN);
      assertThat(((ProcessEffect.ErrnoFault) r.effect()).probability()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("failAfter factory builds FailAfter")
    void failAfter() {
      final ProcessRule r =
          ProcessRule.failAfter(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN, 128L);
      assertThat(r.effect()).isInstanceOf(ProcessEffect.FailAfter.class);
    }

    @Test
    @DisplayName("factories enforce compat matrix")
    void factoryRejectsBadCompat() {
      assertThatThrownBy(() -> ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EINVAL))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> ProcessRule.failAfter(ProcessSelector.FORK, ProcessErrno.EINVAL, 10L))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
