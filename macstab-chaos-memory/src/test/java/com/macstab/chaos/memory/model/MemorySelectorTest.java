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
  @DisplayName("mmap-family errnos: 10 values within the libchaos-memory palette")
  void mmapErrnos() {
    assertThat(MemorySelector.MMAP.validErrnos())
        .containsExactlyInAnyOrder(
            MmapErrno.EACCES,
            MmapErrno.EAGAIN,
            MmapErrno.EBADF,
            MmapErrno.EFAULT,
            MmapErrno.EINVAL,
            MmapErrno.EMFILE,
            MmapErrno.ENFILE,
            MmapErrno.ENODEV,
            MmapErrno.ENOMEM,
            MmapErrno.EPERM);
    assertThat(MemorySelector.MMAP_ANON.validErrnos()).isEqualTo(MemorySelector.MMAP.validErrnos());
    assertThat(MemorySelector.MMAP_FILE.validErrnos()).isEqualTo(MemorySelector.MMAP.validErrnos());
  }

  @Test
  @DisplayName("munmap accepts EFAULT, EINVAL")
  void munmapErrnos() {
    assertThat(MemorySelector.MUNMAP.validErrnos())
        .containsExactlyInAnyOrder(MmapErrno.EFAULT, MmapErrno.EINVAL);
  }

  @Test
  @DisplayName("mprotect accepts EACCES, EFAULT, EINVAL, ENOMEM")
  void mprotectErrnos() {
    assertThat(MemorySelector.MPROTECT.validErrnos())
        .containsExactlyInAnyOrder(
            MmapErrno.EACCES, MmapErrno.EFAULT, MmapErrno.EINVAL, MmapErrno.ENOMEM);
  }

  @Test
  @DisplayName("madvise accepts the 8-errno subset")
  void madviseErrnos() {
    assertThat(MemorySelector.MADVISE.validErrnos())
        .containsExactlyInAnyOrder(
            MmapErrno.EACCES,
            MmapErrno.EAGAIN,
            MmapErrno.EBADF,
            MmapErrno.EFAULT,
            MmapErrno.EINVAL,
            MmapErrno.ENOMEM,
            MmapErrno.ENOSYS,
            MmapErrno.EPERM);
  }

  @Test
  @DisplayName("wildcard is the strict intersection — EINVAL only")
  void wildcardErrnos() {
    assertThat(MemorySelector.WILDCARD.validErrnos()).containsExactly(MmapErrno.EINVAL);
  }

  @Test
  @DisplayName("accepts() probe matches validErrnos contents")
  void acceptsProbe() {
    assertThat(MemorySelector.MUNMAP.accepts(MmapErrno.EINVAL)).isTrue();
    assertThat(MemorySelector.MUNMAP.accepts(MmapErrno.ENOMEM)).isFalse();
    assertThat(MemorySelector.MPROTECT.accepts(MmapErrno.EACCES)).isTrue();
    assertThat(MemorySelector.WILDCARD.accepts(MmapErrno.EAGAIN)).isFalse();
    assertThat(MemorySelector.WILDCARD.accepts(MmapErrno.EINVAL)).isTrue();
  }
}
