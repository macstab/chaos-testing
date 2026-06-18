/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.translators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.macstab.chaos.time.annotation.l1.clock_gettime.ChaosClockGettimeEintr;
import com.macstab.chaos.time.annotation.l1.clock_gettime.ChaosClockGettimeEinval;
import com.macstab.chaos.time.annotation.l1.clock_gettime.ChaosClockGettimeLatency;
import com.macstab.chaos.time.annotation.l1.clock_gettime.ChaosClockGettimeOffset;
import com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepEintr;
import com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepLatency;
import com.macstab.chaos.time.annotation.l1.usleep.ChaosUsleepEinval;
import com.macstab.chaos.time.annotation.l1.wildcard.ChaosWildcardEnosys;
import com.macstab.chaos.time.model.TimeEffect;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;

@DisplayName("Time L1 translators — table-driven")
class TimeTranslatorsTest {

  @ChaosClockGettimeEintr
  static class CgtEintr {}

  @ChaosClockGettimeEinval
  static class CgtEinval {}

  @ChaosNanosleepEintr
  static class NsEintr {}

  @ChaosUsleepEinval
  static class UsEinval {}

  @ChaosWildcardEnosys
  static class WildcardEnosys {}

  @ChaosClockGettimeEinval(probability = 0.05)
  static class CgtEinvalCustom {}

  @ChaosClockGettimeLatency
  static class CgtLatency {}

  @ChaosNanosleepLatency(delayMs = 500L)
  static class NsLatencyCustom {}

  @ChaosClockGettimeOffset
  static class OffsetDefault {}

  @ChaosClockGettimeOffset(deltaMs = 30_000L, probability = 0.1)
  static class OffsetCustom {}

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface PlainNoBinding {}

  @PlainNoBinding
  static class WithPlainNoBinding {}

  private static Annotation pick(final Class<?> clazz) {
    for (final Annotation a : clazz.getAnnotations()) {
      if (a.annotationType().getName().startsWith("java.")) {
        continue;
      }
      return a;
    }
    throw new IllegalStateException("No annotation on " + clazz);
  }

  @Nested
  @DisplayName("TimeErrnoTranslator")
  class Errno {

    static Stream<Arguments> tuples() {
      return Stream.of(
          Arguments.of(CgtEintr.class, TimeSelector.CLOCK_GETTIME, TimeErrno.EINTR),
          Arguments.of(CgtEinval.class, TimeSelector.CLOCK_GETTIME, TimeErrno.EINVAL),
          Arguments.of(NsEintr.class, TimeSelector.NANOSLEEP, TimeErrno.EINTR),
          Arguments.of(UsEinval.class, TimeSelector.USLEEP, TimeErrno.EINVAL),
          Arguments.of(WildcardEnosys.class, TimeSelector.WILDCARD, TimeErrno.ENOSYS));
    }

    @ParameterizedTest(name = "{0} → ({1}, {2})")
    @MethodSource("tuples")
    void translates(final Class<?> fixture, final TimeSelector sel, final TimeErrno err) {
      final TimeRule rule = TimeErrnoTranslator.buildRule(pick(fixture));
      assertThat(rule.selector()).isEqualTo(sel);
      assertThat(rule.effect()).isInstanceOf(TimeEffect.ErrnoFault.class);
      assertThat(((TimeEffect.ErrnoFault) rule.effect()).errno()).isEqualTo(err);
      assertThat(((TimeEffect.ErrnoFault) rule.effect()).probability()).isEqualTo(1.0);
    }

    @Test
    void customProbability() {
      final TimeRule rule = TimeErrnoTranslator.buildRule(pick(CgtEinvalCustom.class));
      assertThat(((TimeEffect.ErrnoFault) rule.effect()).probability()).isEqualTo(0.05);
    }

    @Test
    void missingBinding() {
      assertThatThrownBy(() -> TimeErrnoTranslator.buildRule(pick(WithPlainNoBinding.class)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@TimeErrnoBinding meta-annotation missing");
    }
  }

  @Nested
  @DisplayName("TimeLatencyTranslator")
  class Latency {

    @Test
    void defaultDelay10ms() {
      final TimeRule rule = TimeLatencyTranslator.buildRule(pick(CgtLatency.class));
      assertThat(rule.selector()).isEqualTo(TimeSelector.CLOCK_GETTIME);
      assertThat(((TimeEffect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(10));
    }

    @Test
    void customDelay() {
      final TimeRule rule = TimeLatencyTranslator.buildRule(pick(NsLatencyCustom.class));
      assertThat(rule.selector()).isEqualTo(TimeSelector.NANOSLEEP);
      assertThat(((TimeEffect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void missingBinding() {
      assertThatThrownBy(() -> TimeLatencyTranslator.buildRule(pick(WithPlainNoBinding.class)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@TimeLatencyBinding meta-annotation missing");
    }
  }

  @Nested
  @DisplayName("TimeOffsetTranslator")
  class Offset {

    @Test
    void defaultDelta() {
      final TimeRule rule = TimeOffsetTranslator.buildRule(pick(OffsetDefault.class));
      assertThat(rule.selector()).isEqualTo(TimeSelector.CLOCK_GETTIME);
      assertThat(rule.effect()).isInstanceOf(TimeEffect.Offset.class);
      assertThat(((TimeEffect.Offset) rule.effect()).delta())
          .isEqualTo(Duration.ofMillis(-60_000L));
    }

    @Test
    void customDeltaAndProbability() {
      final TimeRule rule = TimeOffsetTranslator.buildRule(pick(OffsetCustom.class));
      final TimeEffect.Offset off = (TimeEffect.Offset) rule.effect();
      assertThat(off.delta()).isEqualTo(Duration.ofMillis(30_000L));
      assertThat(off.probability()).isEqualTo(0.1);
    }
  }
}
