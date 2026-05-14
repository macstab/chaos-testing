/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("TimeRule (unit)")
class TimeRuleTest {

  @Nested
  @DisplayName("null checks")
  class NullChecks {
    @Test
    @DisplayName("null selector / clock-Optional / effect rejected")
    void nulls() {
      assertThatThrownBy(
              () ->
                  new TimeRule(
                      null, Optional.empty(), TimeEffect.errno(TimeErrno.EINVAL)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(
              () -> new TimeRule(TimeSelector.NANOSLEEP, null, TimeEffect.errno(TimeErrno.EINTR)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(
              () -> new TimeRule(TimeSelector.NANOSLEEP, Optional.empty(), null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("ERRNO and LATENCY: valid on every selector (no clock qualifier)")
  class UnqualifiedMatrix {
    @ParameterizedTest
    @EnumSource(TimeSelector.class)
    void errnoEverySelector(final TimeSelector selector) {
      new TimeRule(selector, Optional.empty(), TimeEffect.errno(TimeErrno.EINTR));
    }

    @ParameterizedTest
    @EnumSource(TimeSelector.class)
    void latencyEverySelector(final TimeSelector selector) {
      new TimeRule(selector, Optional.empty(), TimeEffect.latency(Duration.ofMillis(10)));
    }
  }

  @Nested
  @DisplayName("clock qualifier compatibility")
  class ClockQualifier {
    @ParameterizedTest
    @EnumSource(TimeClock.class)
    @DisplayName("any TimeClock paired with CLOCK_GETTIME is accepted")
    void clockGettimePairs(final TimeClock clock) {
      new TimeRule(
          TimeSelector.CLOCK_GETTIME, Optional.of(clock), TimeEffect.errno(TimeErrno.EINVAL));
    }

    @Test
    @DisplayName("TimeClock paired with NANOSLEEP / USLEEP / WILDCARD is rejected")
    void clockOnNonClockGettimeRejected() {
      for (final TimeSelector s : new TimeSelector[] {
        TimeSelector.NANOSLEEP, TimeSelector.USLEEP, TimeSelector.WILDCARD
      }) {
        assertThatThrownBy(
                () ->
                    new TimeRule(
                        s, Optional.of(TimeClock.MONOTONIC), TimeEffect.errno(TimeErrno.EINTR)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("clock qualifier")
            .hasMessageContaining(s.wireForm());
      }
    }
  }

  @Nested
  @DisplayName("OFFSET effect compatibility")
  class OffsetMatrix {
    @Test
    @DisplayName("OFFSET on CLOCK_GETTIME without clock qualifier is accepted")
    void offsetUnqualified() {
      new TimeRule(
          TimeSelector.CLOCK_GETTIME, Optional.empty(), TimeEffect.offset(Duration.ofMillis(500)));
    }

    @ParameterizedTest
    @EnumSource(TimeClock.class)
    @DisplayName("OFFSET on CLOCK_GETTIME with any clock qualifier is accepted")
    void offsetPerClock(final TimeClock clock) {
      new TimeRule(
          TimeSelector.CLOCK_GETTIME,
          Optional.of(clock),
          TimeEffect.offset(Duration.ofMillis(-1500)));
    }

    @Test
    @DisplayName("OFFSET on NANOSLEEP / USLEEP / WILDCARD is rejected with informative message")
    void offsetRejected() {
      nonClockGettime()
          .forEach(
              s ->
                  assertThatThrownBy(
                          () ->
                              new TimeRule(
                                  s,
                                  Optional.empty(),
                                  TimeEffect.offset(Duration.ofMillis(100))))
                      .isInstanceOf(IllegalArgumentException.class)
                      .hasMessageContaining("OFFSET")
                      .hasMessageContaining(s.wireForm()));
    }

    static Stream<TimeSelector> nonClockGettime() {
      return Stream.of(TimeSelector.NANOSLEEP, TimeSelector.USLEEP, TimeSelector.WILDCARD);
    }
  }

  @Nested
  @DisplayName("static factories")
  class Factories {
    @Test
    @DisplayName("errno factory builds ErrnoFault on the unqualified selector")
    void errno() {
      final TimeRule r = TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR, 0.5);
      assertThat(r.selector()).isEqualTo(TimeSelector.NANOSLEEP);
      assertThat(r.clock()).isEmpty();
      assertThat(r.effect()).isInstanceOf(TimeEffect.ErrnoFault.class);
    }

    @Test
    @DisplayName("errno factory with TimeClock builds qualified CLOCK_GETTIME rule")
    void errnoPerClock() {
      final TimeRule r = TimeRule.errno(TimeClock.MONOTONIC, TimeErrno.EINVAL, 1.0);
      assertThat(r.selector()).isEqualTo(TimeSelector.CLOCK_GETTIME);
      assertThat(r.clock()).contains(TimeClock.MONOTONIC);
    }

    @Test
    @DisplayName("latency factory defaults to unqualified CLOCK_GETTIME when clock supplied")
    void latencyPerClock() {
      final TimeRule r = TimeRule.latency(TimeClock.REALTIME, Duration.ofMillis(50));
      assertThat(r.selector()).isEqualTo(TimeSelector.CLOCK_GETTIME);
      assertThat(r.clock()).contains(TimeClock.REALTIME);
    }

    @Test
    @DisplayName("offset factory unqualified")
    void offsetUnqualified() {
      final TimeRule r = TimeRule.offset(Duration.ofMillis(-1500));
      assertThat(r.selector()).isEqualTo(TimeSelector.CLOCK_GETTIME);
      assertThat(r.clock()).isEmpty();
      assertThat(r.effect()).isInstanceOf(TimeEffect.Offset.class);
    }

    @Test
    @DisplayName("offset factory per-clock")
    void offsetPerClock() {
      final TimeRule r = TimeRule.offset(TimeClock.MONOTONIC, Duration.ofMillis(-1500));
      assertThat(r.clock()).contains(TimeClock.MONOTONIC);
    }

    @Test
    @DisplayName("factories enforce compat matrix — null clock rejected, OFFSET-on-nanosleep rejected")
    void factoryRejectsBadCompat() {
      assertThatThrownBy(() -> TimeRule.errno((TimeClock) null, TimeErrno.EINVAL, 1.0))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> TimeRule.latency((TimeClock) null, Duration.ofMillis(10)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> TimeRule.offset((TimeClock) null, Duration.ofMillis(10)))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
