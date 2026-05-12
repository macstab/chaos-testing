/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MemorySelector (unit)")
class MemorySelectorTest {

  @Test
  @DisplayName("wireForm tokens match libchaos-memory grammar")
  void wireForms() {
    assertThat(MemorySelector.MMAP.wireForm()).isEqualTo("mmap");
    assertThat(MemorySelector.MMAP_ANON.wireForm()).isEqualTo("mmap/anon");
    assertThat(MemorySelector.MMAP_FILE.wireForm()).isEqualTo("mmap/file");
    assertThat(MemorySelector.MUNMAP.wireForm()).isEqualTo("munmap");
    assertThat(MemorySelector.MPROTECT.wireForm()).isEqualTo("mprotect");
    assertThat(MemorySelector.MADVISE.wireForm()).isEqualTo("madvise");
    assertThat(MemorySelector.WILDCARD.wireForm()).isEqualTo("*");
  }

  @Test
  @DisplayName("mmap-family errnos: 10 values (EACCES..ETXTBSY excluding EIO)")
  void mmapErrnos() {
    assertThat(MemorySelector.MMAP.validErrnos())
        .containsExactlyInAnyOrder(
            MmapErrno.EACCES,
            MmapErrno.EAGAIN,
            MmapErrno.EBADF,
            MmapErrno.EINVAL,
            MmapErrno.ENFILE,
            MmapErrno.ENODEV,
            MmapErrno.ENOMEM,
            MmapErrno.EOVERFLOW,
            MmapErrno.EPERM,
            MmapErrno.ETXTBSY);
    assertThat(MemorySelector.MMAP_ANON.validErrnos()).isEqualTo(MemorySelector.MMAP.validErrnos());
    assertThat(MemorySelector.MMAP_FILE.validErrnos()).isEqualTo(MemorySelector.MMAP.validErrnos());
  }

  @Test
  @DisplayName("munmap accepts only EINVAL")
  void munmapErrnos() {
    assertThat(MemorySelector.MUNMAP.validErrnos()).containsExactly(MmapErrno.EINVAL);
  }

  @Test
  @DisplayName("mprotect accepts EACCES, EINVAL, ENOMEM")
  void mprotectErrnos() {
    assertThat(MemorySelector.MPROTECT.validErrnos())
        .containsExactlyInAnyOrder(MmapErrno.EACCES, MmapErrno.EINVAL, MmapErrno.ENOMEM);
  }

  @Test
  @DisplayName("madvise accepts the 7-errno subset")
  void madviseErrnos() {
    assertThat(MemorySelector.MADVISE.validErrnos())
        .containsExactlyInAnyOrder(
            MmapErrno.EACCES,
            MmapErrno.EAGAIN,
            MmapErrno.EBADF,
            MmapErrno.EINVAL,
            MmapErrno.EIO,
            MmapErrno.ENOMEM,
            MmapErrno.EPERM);
  }

  @Test
  @DisplayName("wildcard is the strict intersection — EINVAL + ENOMEM only")
  void wildcardErrnos() {
    assertThat(MemorySelector.WILDCARD.validErrnos())
        .containsExactlyInAnyOrder(MmapErrno.EINVAL, MmapErrno.ENOMEM);
  }

  @Test
  @DisplayName("accepts() probe matches validErrnos contents")
  void acceptsProbe() {
    assertThat(MemorySelector.MUNMAP.accepts(MmapErrno.EINVAL)).isTrue();
    assertThat(MemorySelector.MUNMAP.accepts(MmapErrno.ENOMEM)).isFalse();
    assertThat(MemorySelector.MPROTECT.accepts(MmapErrno.EACCES)).isTrue();
    assertThat(MemorySelector.WILDCARD.accepts(MmapErrno.EAGAIN)).isFalse();
    assertThat(MemorySelector.WILDCARD.accepts(MmapErrno.ENOMEM)).isTrue();
  }
}
