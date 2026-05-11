/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Errno (unit)")
class ErrnoTest {

  @Test
  @DisplayName("wireForm matches enum name verbatim")
  void wireFormVerbatim() {
    for (final Errno e : Errno.values()) {
      assertThat(e.wireForm()).isEqualTo(e.name());
    }
  }

  @Test
  @DisplayName("8 file errnos match libchaos-io palette")
  void paletteBound() {
    assertThat(Errno.values())
        .containsExactlyInAnyOrder(
            Errno.EIO,
            Errno.ENOSPC,
            Errno.EDQUOT,
            Errno.EROFS,
            Errno.EACCES,
            Errno.EMFILE,
            Errno.ENFILE,
            Errno.ENOENT);
  }
}
