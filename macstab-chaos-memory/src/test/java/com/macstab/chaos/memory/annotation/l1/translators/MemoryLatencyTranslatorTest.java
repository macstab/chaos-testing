/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.translators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.macstab.chaos.memory.annotation.l1.madvise.ChaosMadviseLatency;
import com.macstab.chaos.memory.annotation.l1.mmap.ChaosMmapLatency;
import com.macstab.chaos.memory.annotation.l1.mmap_anon.ChaosMmapAnonLatency;
import com.macstab.chaos.memory.annotation.l1.mmap_file.ChaosMmapFileLatency;
import com.macstab.chaos.memory.annotation.l1.mprotect.ChaosMprotectLatency;
import com.macstab.chaos.memory.annotation.l1.munmap.ChaosMunmapLatency;
import com.macstab.chaos.memory.annotation.l1.wildcard.ChaosWildcardLatency;
import com.macstab.chaos.memory.model.MemoryEffect;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;

@DisplayName("MemoryLatencyTranslator.buildRule — one annotation per selector")
class MemoryLatencyTranslatorTest {

  @ChaosMmapLatency
  static class DefaultMmap {}

  @ChaosMmapAnonLatency
  static class DefaultMmapAnon {}

  @ChaosMmapFileLatency
  static class DefaultMmapFile {}

  @ChaosMunmapLatency
  static class DefaultMunmap {}

  @ChaosMprotectLatency
  static class DefaultMprotect {}

  @ChaosMadviseLatency
  static class DefaultMadvise {}

  @ChaosWildcardLatency
  static class DefaultWildcard {}

  @ChaosMmapAnonLatency(delayMs = 250L)
  static class CustomDelay {}

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface PlainNoBinding {}

  @PlainNoBinding
  static class WithPlainNoBinding {}

  static Stream<Arguments> annotationToSelector() {
    return Stream.of(
        Arguments.of(DefaultMmap.class, MemorySelector.MMAP),
        Arguments.of(DefaultMmapAnon.class, MemorySelector.MMAP_ANON),
        Arguments.of(DefaultMmapFile.class, MemorySelector.MMAP_FILE),
        Arguments.of(DefaultMunmap.class, MemorySelector.MUNMAP),
        Arguments.of(DefaultMprotect.class, MemorySelector.MPROTECT),
        Arguments.of(DefaultMadvise.class, MemorySelector.MADVISE),
        Arguments.of(DefaultWildcard.class, MemorySelector.WILDCARD));
  }

  @ParameterizedTest(name = "{0} → {1}")
  @MethodSource("annotationToSelector")
  void translatesEverySelector(final Class<?> fixtureClass, final MemorySelector expected) {
    final Annotation a = pickL1Annotation(fixtureClass);
    final MemoryRule rule = MemoryLatencyTranslator.buildRule(a);

    assertThat(rule.selector()).isEqualTo(expected);
    assertThat(rule.effect()).isInstanceOf(MemoryEffect.Latency.class);
    final MemoryEffect.Latency lat = (MemoryEffect.Latency) rule.effect();
    assertThat(lat.delay()).isEqualTo(Duration.ofMillis(50));
  }

  @Test
  @DisplayName("custom delayMs propagates into the Latency effect")
  void customDelay() {
    final Annotation a = pickL1Annotation(CustomDelay.class);
    final MemoryRule rule = MemoryLatencyTranslator.buildRule(a);
    final MemoryEffect.Latency lat = (MemoryEffect.Latency) rule.effect();
    assertThat(lat.delay()).isEqualTo(Duration.ofMillis(250));
  }

  @Test
  @DisplayName("annotation without @MemoryLatencyBinding → IllegalStateException")
  void missingBinding() {
    final Annotation a = pickL1Annotation(WithPlainNoBinding.class);
    assertThatThrownBy(() -> MemoryLatencyTranslator.buildRule(a))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("@MemoryLatencyBinding meta-annotation missing");
  }

  private static Annotation pickL1Annotation(final Class<?> clazz) {
    for (final Annotation a : clazz.getAnnotations()) {
      if (a.annotationType().getName().startsWith("java.")) {
        continue;
      }
      return a;
    }
    throw new IllegalStateException("No annotation found on " + clazz);
  }
}
