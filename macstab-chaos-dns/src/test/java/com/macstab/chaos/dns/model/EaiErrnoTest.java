/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EaiErrno (unit)")
class EaiErrnoTest {

  @Test
  @DisplayName("wireForm matches enum name verbatim")
  void wireFormVerbatim() {
    for (final EaiErrno e : EaiErrno.values()) {
      assertThat(e.wireForm()).isEqualTo(e.name());
    }
  }

  @Test
  @DisplayName("5 EAI codes match libchaos-dns palette")
  void paletteBound() {
    assertThat(EaiErrno.values())
        .containsExactlyInAnyOrder(
            EaiErrno.EAI_AGAIN,
            EaiErrno.EAI_FAIL,
            EaiErrno.EAI_NONAME,
            EaiErrno.EAI_MEMORY,
            EaiErrno.EAI_SYSTEM);
  }
}
