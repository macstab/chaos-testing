/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TimeErrno (unit)")
class TimeErrnoTest {

  @Test
  @DisplayName("wireForm matches enum name verbatim")
  void wireFormVerbatim() {
    for (final TimeErrno e : TimeErrno.values()) {
      assertThat(e.wireForm()).isEqualTo(e.name());
    }
  }

  @Test
  @DisplayName("6 errnos in the libchaos-time palette")
  void paletteBound() {
    assertThat(TimeErrno.values()).hasSize(6);
  }

  @Test
  @DisplayName("palette contains exactly the six C-parser-accepted names")
  void paletteContents() {
    assertThat(TimeErrno.values())
        .containsExactlyInAnyOrder(
            TimeErrno.EAGAIN,
            TimeErrno.EFAULT,
            TimeErrno.EINTR,
            TimeErrno.EINVAL,
            TimeErrno.ENOSYS,
            TimeErrno.EPERM);
  }
}
