/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessErrno (unit)")
class ProcessErrnoTest {

  @Test
  @DisplayName("wireForm matches enum name verbatim")
  void wireFormVerbatim() {
    for (final ProcessErrno e : ProcessErrno.values()) {
      assertThat(e.wireForm()).isEqualTo(e.name());
    }
  }

  @Test
  @DisplayName("12 errnos in the union across all process symbols")
  void paletteBound() {
    assertThat(ProcessErrno.values()).hasSize(12);
  }
}
