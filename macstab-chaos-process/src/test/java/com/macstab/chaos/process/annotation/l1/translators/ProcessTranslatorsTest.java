/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.translators;

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

import com.macstab.chaos.process.annotation.l1.execve.ChaosExecveE2big;
import com.macstab.chaos.process.annotation.l1.execve.ChaosExecveEnomem;
import com.macstab.chaos.process.annotation.l1.execve.ChaosExecveLatency;
import com.macstab.chaos.process.annotation.l1.fork.ChaosForkEagain;
import com.macstab.chaos.process.annotation.l1.fork.ChaosForkEagainFailAfter;
import com.macstab.chaos.process.annotation.l1.fork.ChaosForkEnomem;
import com.macstab.chaos.process.annotation.l1.fork.ChaosForkLatency;
import com.macstab.chaos.process.annotation.l1.pthread_create.ChaosPthreadCreateEagain;
import com.macstab.chaos.process.annotation.l1.pthread_create.ChaosPthreadCreateEagainFailAfter;
import com.macstab.chaos.process.annotation.l1.waitpid.ChaosWaitpidEchild;
import com.macstab.chaos.process.annotation.l1.waitpid.ChaosWaitpidEintr;
import com.macstab.chaos.process.annotation.l1.wildcard.ChaosWildcardEinval;
import com.macstab.chaos.process.model.ProcessEffect;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessRule;
import com.macstab.chaos.process.model.ProcessSelector;

@DisplayName("Process L1 translators — table-driven")
class ProcessTranslatorsTest {

  // ==================== Errno fixtures ====================

  @ChaosForkEagain
  static class ForkEagain {}

  @ChaosForkEnomem
  static class ForkEnomem {}

  @ChaosPthreadCreateEagain
  static class PthreadEagain {}

  @ChaosExecveE2big
  static class ExecveE2big {}

  @ChaosExecveEnomem
  static class ExecveEnomem {}

  @ChaosWaitpidEchild
  static class WaitpidEchild {}

  @ChaosWaitpidEintr
  static class WaitpidEintr {}

  @ChaosWildcardEinval
  static class WildcardEinval {}

  @ChaosForkEagain(probability = 0.001)
  static class ForkEagainCustomProb {}

  // ==================== Latency fixtures ====================

  @ChaosForkLatency
  static class ForkLatency {}

  @ChaosExecveLatency
  static class ExecveLatency {}

  @ChaosForkLatency(delayMs = 500L)
  static class ForkLatencyCustom {}

  // ==================== FailAfter fixtures ====================

  @ChaosForkEagainFailAfter
  static class ForkEagainFA {}

  @ChaosPthreadCreateEagainFailAfter(successesBeforeFailure = 128L)
  static class PthreadEagainFA128 {}

  // ==================== Helpers ====================

  private static Annotation pickL1Annotation(final Class<?> clazz) {
    for (final Annotation a : clazz.getAnnotations()) {
      if (a.annotationType().getName().startsWith("java.")) {
        continue;
      }
      return a;
    }
    throw new IllegalStateException("No annotation found on " + clazz);
  }

  // ==================== ErrnoTranslator ====================

  @Nested
  @DisplayName("ProcessErrnoTranslator")
  class Errno {

    static Stream<Arguments> tuples() {
      return Stream.of(
          Arguments.of(ForkEagain.class, ProcessSelector.FORK, ProcessErrno.EAGAIN),
          Arguments.of(ForkEnomem.class, ProcessSelector.FORK, ProcessErrno.ENOMEM),
          Arguments.of(PthreadEagain.class, ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN),
          Arguments.of(ExecveE2big.class, ProcessSelector.EXECVE, ProcessErrno.E2BIG),
          Arguments.of(ExecveEnomem.class, ProcessSelector.EXECVE, ProcessErrno.ENOMEM),
          Arguments.of(WaitpidEchild.class, ProcessSelector.WAITPID, ProcessErrno.ECHILD),
          Arguments.of(WaitpidEintr.class, ProcessSelector.WAITPID, ProcessErrno.EINTR),
          Arguments.of(WildcardEinval.class, ProcessSelector.WILDCARD, ProcessErrno.EINVAL));
    }

    @ParameterizedTest(name = "{0} → ({1}, {2})")
    @MethodSource("tuples")
    void translates(final Class<?> fixture, final ProcessSelector sel, final ProcessErrno err) {
      final ProcessRule rule = ProcessErrnoTranslator.buildRule(pickL1Annotation(fixture));
      assertThat(rule.selector()).isEqualTo(sel);
      assertThat(rule.effect()).isInstanceOf(ProcessEffect.ErrnoFault.class);
      final ProcessEffect.ErrnoFault f = (ProcessEffect.ErrnoFault) rule.effect();
      assertThat(f.errno()).isEqualTo(err);
      assertThat(f.probability()).isEqualTo(1.0);
    }

    @Test
    void customProbabilityPropagates() {
      final ProcessRule rule =
          ProcessErrnoTranslator.buildRule(pickL1Annotation(ForkEagainCustomProb.class));
      assertThat(((ProcessEffect.ErrnoFault) rule.effect()).probability()).isEqualTo(0.001);
    }

    @Test
    void missingBindingThrows() {
      assertThatThrownBy(() -> ProcessErrnoTranslator.buildRule(plainAnnotation()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@ProcessErrnoBinding meta-annotation missing");
    }
  }

  // ==================== LatencyTranslator ====================

  @Nested
  @DisplayName("ProcessLatencyTranslator")
  class Latency {

    static Stream<Arguments> tuples() {
      return Stream.of(
          Arguments.of(ForkLatency.class, ProcessSelector.FORK),
          Arguments.of(ExecveLatency.class, ProcessSelector.EXECVE));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("tuples")
    void translates(final Class<?> fixture, final ProcessSelector sel) {
      final ProcessRule rule = ProcessLatencyTranslator.buildRule(pickL1Annotation(fixture));
      assertThat(rule.selector()).isEqualTo(sel);
      assertThat(rule.effect()).isInstanceOf(ProcessEffect.Latency.class);
      assertThat(((ProcessEffect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void customDelayPropagates() {
      final ProcessRule rule =
          ProcessLatencyTranslator.buildRule(pickL1Annotation(ForkLatencyCustom.class));
      assertThat(((ProcessEffect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void missingBindingThrows() {
      assertThatThrownBy(() -> ProcessLatencyTranslator.buildRule(plainAnnotation()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@ProcessLatencyBinding meta-annotation missing");
    }
  }

  // ==================== FailAfterTranslator ====================

  @Nested
  @DisplayName("ProcessFailAfterTranslator")
  class FailAfter {

    @Test
    void defaultCountZero() {
      final ProcessRule rule =
          ProcessFailAfterTranslator.buildRule(pickL1Annotation(ForkEagainFA.class));
      assertThat(rule.selector()).isEqualTo(ProcessSelector.FORK);
      assertThat(rule.effect()).isInstanceOf(ProcessEffect.FailAfter.class);
      final ProcessEffect.FailAfter fa = (ProcessEffect.FailAfter) rule.effect();
      assertThat(fa.errno()).isEqualTo(ProcessErrno.EAGAIN);
      assertThat(fa.count()).isEqualTo(0L);
    }

    @Test
    void customCountPropagates() {
      final ProcessRule rule =
          ProcessFailAfterTranslator.buildRule(pickL1Annotation(PthreadEagainFA128.class));
      final ProcessEffect.FailAfter fa = (ProcessEffect.FailAfter) rule.effect();
      assertThat(fa.errno()).isEqualTo(ProcessErrno.EAGAIN);
      assertThat(fa.count()).isEqualTo(128L);
    }

    @Test
    void missingBindingThrows() {
      assertThatThrownBy(() -> ProcessFailAfterTranslator.buildRule(plainAnnotation()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@ProcessFailAfterBinding meta-annotation missing");
    }
  }

  // ==================== shared no-binding fixture ====================

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface PlainNoBinding {}

  @PlainNoBinding
  static class WithPlainNoBinding {}

  private static Annotation plainAnnotation() {
    return pickL1Annotation(WithPlainNoBinding.class);
  }
}
