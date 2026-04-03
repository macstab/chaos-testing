/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Signal}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("Signal")
class SignalTest {

  @Nested
  @DisplayName("signal values")
  class Values {

    @Test
    @DisplayName("SIGTERM is 15")
    void sigtermIs15() {
      assertThat(Signal.SIGTERM.value()).isEqualTo(15);
    }

    @Test
    @DisplayName("SIGKILL is 9")
    void sigkillIs9() {
      assertThat(Signal.SIGKILL.value()).isEqualTo(9);
    }

    @Test
    @DisplayName("SIGSTOP is 19")
    void sigstopIs19() {
      assertThat(Signal.SIGSTOP.value()).isEqualTo(19);
    }

    @Test
    @DisplayName("SIGCONT is 18")
    void sigcontIs18() {
      assertThat(Signal.SIGCONT.value()).isEqualTo(18);
    }

    @Test
    @DisplayName("all four signals are defined")
    void allFourDefined() {
      assertThat(Signal.values()).hasSize(4)
          .containsExactlyInAnyOrder(
              Signal.SIGTERM, Signal.SIGKILL, Signal.SIGSTOP, Signal.SIGCONT);
    }
  }
}
