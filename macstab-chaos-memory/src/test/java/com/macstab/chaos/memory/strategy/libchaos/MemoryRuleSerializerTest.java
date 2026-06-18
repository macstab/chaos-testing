/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

@DisplayName("MemoryRuleSerializer (unit)")
class MemoryRuleSerializerTest {

  @Test
  @DisplayName("ERRNO on mmap/anon — probability 1.0 omitted")
  void errnoOnAnonProb1() {
    assertThat(
            MemoryRuleSerializer.serialize(
                MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM)))
        .isEqualTo("mmap/anon:ERRNO:ENOMEM");
  }

  @Test
  @DisplayName("ERRNO with sub-1.0 probability — @suffix preserved")
  void errnoLowProb() {
    assertThat(
            MemoryRuleSerializer.serialize(
                MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM, 0.001)))
        .isEqualTo("mmap/anon:ERRNO:ENOMEM@0.001");
  }

  @Test
  @DisplayName("LATENCY on madvise")
  void latencyOnMadvise() {
    assertThat(
            MemoryRuleSerializer.serialize(
                MemoryRule.latency(MemorySelector.MADVISE, Duration.ofMillis(250))))
        .isEqualTo("madvise:LATENCY:250");
  }

  @Test
  @DisplayName("mmap ENOMEM — OOM simulation")
  void mmapOom() {
    assertThat(
            MemoryRuleSerializer.serialize(
                MemoryRule.errno(MemorySelector.MMAP, MmapErrno.ENOMEM, 0.05)))
        .isEqualTo("mmap:ERRNO:ENOMEM@0.05");
  }

  @Test
  @DisplayName("mprotect EACCES — JIT failure")
  void mprotectJit() {
    assertThat(
            MemoryRuleSerializer.serialize(
                MemoryRule.errno(MemorySelector.MPROTECT, MmapErrno.EACCES, 0.5)))
        .isEqualTo("mprotect:ERRNO:EACCES@0.5");
  }

  @Test
  @DisplayName("munmap EINVAL — leak simulation")
  void munmapLeak() {
    assertThat(
            MemoryRuleSerializer.serialize(
                MemoryRule.errno(MemorySelector.MUNMAP, MmapErrno.EINVAL)))
        .isEqualTo("munmap:ERRNO:EINVAL");
  }

  @Test
  @DisplayName("null rule rejected")
  void nullRule() {
    assertThatThrownBy(() -> MemoryRuleSerializer.serialize(null))
        .isInstanceOf(NullPointerException.class);
  }
}
