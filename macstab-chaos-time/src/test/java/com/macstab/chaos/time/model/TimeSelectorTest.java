/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TimeSelector (unit)")
class TimeSelectorTest {

  @Test
  @DisplayName("wireForm tokens match libchaos-time grammar")
  void wireForms() {
    assertThat(TimeSelector.CLOCK_GETTIME.wireForm()).isEqualTo("clock_gettime");
    assertThat(TimeSelector.NANOSLEEP.wireForm()).isEqualTo("nanosleep");
    assertThat(TimeSelector.USLEEP.wireForm()).isEqualTo("usleep");
    assertThat(TimeSelector.WILDCARD.wireForm()).isEqualTo("*");
  }

  @Test
  @DisplayName("only CLOCK_GETTIME accepts the OFFSET effect")
  void acceptsOffset() {
    assertThat(TimeSelector.CLOCK_GETTIME.acceptsOffset()).isTrue();
    assertThat(TimeSelector.NANOSLEEP.acceptsOffset()).isFalse();
    assertThat(TimeSelector.USLEEP.acceptsOffset()).isFalse();
    assertThat(TimeSelector.WILDCARD.acceptsOffset()).isFalse();
  }

  @Test
  @DisplayName("only CLOCK_GETTIME accepts a TimeClock qualifier")
  void acceptsClockQualifier() {
    assertThat(TimeSelector.CLOCK_GETTIME.acceptsClockQualifier()).isTrue();
    assertThat(TimeSelector.NANOSLEEP.acceptsClockQualifier()).isFalse();
    assertThat(TimeSelector.USLEEP.acceptsClockQualifier()).isFalse();
    assertThat(TimeSelector.WILDCARD.acceptsClockQualifier()).isFalse();
  }

  @Test
  @DisplayName("4 base selectors (sub-selectors qualified via TimeClock)")
  void paletteBound() {
    assertThat(TimeSelector.values()).hasSize(4);
  }
}
