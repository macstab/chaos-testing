/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MmapErrno (unit)")
class MmapErrnoTest {

  @Test
  @DisplayName("wireForm matches enum name verbatim")
  void wireFormVerbatim() {
    for (final MmapErrno e : MmapErrno.values()) {
      assertThat(e.wireForm()).isEqualTo(e.name());
    }
  }

  @Test
  @DisplayName("11 errnos across the mmap/munmap/mprotect/madvise unions")
  void paletteBound() {
    assertThat(MmapErrno.values()).hasSize(11);
  }
}
