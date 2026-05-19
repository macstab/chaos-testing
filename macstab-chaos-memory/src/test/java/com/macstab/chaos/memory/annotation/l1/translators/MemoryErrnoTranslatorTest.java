/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.translators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.macstab.chaos.memory.annotation.l1.MemoryErrnoBinding;
import com.macstab.chaos.memory.annotation.l1.madvise.ChaosMadviseEacces;
import com.macstab.chaos.memory.annotation.l1.madvise.ChaosMadviseEnomem;
import com.macstab.chaos.memory.annotation.l1.madvise.ChaosMadviseEnosys;
import com.macstab.chaos.memory.annotation.l1.mmap.ChaosMmapEnomem;
import com.macstab.chaos.memory.annotation.l1.mmap_anon.ChaosMmapAnonEnomem;
import com.macstab.chaos.memory.annotation.l1.mmap_anon.ChaosMmapAnonEperm;
import com.macstab.chaos.memory.annotation.l1.mmap_file.ChaosMmapFileEmfile;
import com.macstab.chaos.memory.annotation.l1.mprotect.ChaosMprotectEacces;
import com.macstab.chaos.memory.annotation.l1.munmap.ChaosMunmapEfault;
import com.macstab.chaos.memory.annotation.l1.munmap.ChaosMunmapEinval;
import com.macstab.chaos.memory.annotation.l1.wildcard.ChaosWildcardEinval;
import com.macstab.chaos.memory.model.MemoryEffect;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

@DisplayName("MemoryErrnoTranslator.buildRule — table-driven across the L1 errno surface")
class MemoryErrnoTranslatorTest {

  // ==================== Fixture annotations carrying default probability ====================

  @ChaosMmapAnonEnomem
  static class DefaultMmapAnonEnomem {}

  @ChaosMmapAnonEnomem(probability = 0.001)
  static class CustomProbability {}

  @ChaosMmapAnonEperm
  static class DefaultMmapAnonEperm {}

  @ChaosMmapEnomem
  static class DefaultMmapEnomem {}

  @ChaosMmapFileEmfile
  static class DefaultMmapFileEmfile {}

  @ChaosMunmapEfault
  static class DefaultMunmapEfault {}

  @ChaosMunmapEinval
  static class DefaultMunmapEinval {}

  @ChaosMprotectEacces
  static class DefaultMprotectEacces {}

  @ChaosMadviseEacces
  static class DefaultMadviseEacces {}

  @ChaosMadviseEnomem
  static class DefaultMadviseEnomem {}

  @ChaosMadviseEnosys
  static class DefaultMadviseEnosys {}

  @ChaosWildcardEinval
  static class DefaultWildcardEinval {}

  /** Plain annotation without {@link MemoryErrnoBinding} — for the missing-binding error path. */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface PlainNoBinding {}

  @PlainNoBinding
  static class WithPlainNoBinding {}

  // ==================== Table-driven correctness ====================

  static Stream<Arguments> annotationToTuple() {
    return Stream.of(
        Arguments.of(DefaultMmapAnonEnomem.class, MemorySelector.MMAP_ANON, MmapErrno.ENOMEM),
        Arguments.of(DefaultMmapAnonEperm.class, MemorySelector.MMAP_ANON, MmapErrno.EPERM),
        Arguments.of(DefaultMmapEnomem.class, MemorySelector.MMAP, MmapErrno.ENOMEM),
        Arguments.of(DefaultMmapFileEmfile.class, MemorySelector.MMAP_FILE, MmapErrno.EMFILE),
        Arguments.of(DefaultMunmapEfault.class, MemorySelector.MUNMAP, MmapErrno.EFAULT),
        Arguments.of(DefaultMunmapEinval.class, MemorySelector.MUNMAP, MmapErrno.EINVAL),
        Arguments.of(DefaultMprotectEacces.class, MemorySelector.MPROTECT, MmapErrno.EACCES),
        Arguments.of(DefaultMadviseEacces.class, MemorySelector.MADVISE, MmapErrno.EACCES),
        Arguments.of(DefaultMadviseEnomem.class, MemorySelector.MADVISE, MmapErrno.ENOMEM),
        Arguments.of(DefaultMadviseEnosys.class, MemorySelector.MADVISE, MmapErrno.ENOSYS),
        Arguments.of(DefaultWildcardEinval.class, MemorySelector.WILDCARD, MmapErrno.EINVAL));
  }

  @ParameterizedTest(name = "{0} → ({1}, {2})")
  @MethodSource("annotationToTuple")
  @DisplayName("each L1 annotation maps to the exact (selector, errno) tuple declared by its binding")
  void translatesEveryTuple(
      final Class<?> fixtureClass, final MemorySelector selector, final MmapErrno errno) {

    final Annotation a = pickL1Annotation(fixtureClass);
    final MemoryRule rule = MemoryErrnoTranslator.buildRule(a);

    assertThat(rule.selector()).isEqualTo(selector);
    assertThat(rule.effect()).isInstanceOf(MemoryEffect.ErrnoFault.class);
    final MemoryEffect.ErrnoFault fault = (MemoryEffect.ErrnoFault) rule.effect();
    assertThat(fault.errno()).isEqualTo(errno);
    assertThat(fault.probability()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("custom probability is propagated into the ErrnoFault effect")
  void customProbability() {
    final Annotation a = pickL1Annotation(CustomProbability.class);
    final MemoryRule rule = MemoryErrnoTranslator.buildRule(a);

    final MemoryEffect.ErrnoFault fault = (MemoryEffect.ErrnoFault) rule.effect();
    assertThat(fault.probability()).isEqualTo(0.001);
  }

  @Test
  @DisplayName("annotation without @MemoryErrnoBinding → IllegalStateException with class name")
  void missingBinding() {
    final Annotation a = pickL1Annotation(WithPlainNoBinding.class);
    assertThatThrownBy(() -> MemoryErrnoTranslator.buildRule(a))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("@MemoryErrnoBinding meta-annotation missing")
        .hasMessageContaining("PlainNoBinding");
  }

  // ==================== Annotation discovery helper ====================

  /** Returns the first annotation on {@code clazz} (test fixtures carry exactly one L1 + maybe binding). */
  private static Annotation pickL1Annotation(final Class<?> clazz) {
    for (final Annotation a : clazz.getAnnotations()) {
      // skip JDK / framework annotations
      if (a.annotationType().getName().startsWith("java.")) {
        continue;
      }
      return a;
    }
    throw new IllegalStateException("No annotation found on " + clazz);
  }
}
