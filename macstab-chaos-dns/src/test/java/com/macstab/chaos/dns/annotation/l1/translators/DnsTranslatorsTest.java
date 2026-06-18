/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.translators;

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

import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEaiagain;
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEainoname;
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardLatency;
import com.macstab.chaos.dns.annotation.l1.reverse.ChaosReverseEainoname;
import com.macstab.chaos.dns.annotation.l1.reverse.ChaosReverseLatency;
import com.macstab.chaos.dns.annotation.l1.wildcard.ChaosWildcardEaifail;
import com.macstab.chaos.dns.annotation.l1.wildcard.ChaosWildcardEaisystem;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;
import com.macstab.chaos.dns.model.Effect;

@DisplayName("DNS L1 translators — table-driven")
class DnsTranslatorsTest {

  @ChaosForwardEaiagain
  static class FwdEaiagain {}

  @ChaosForwardEainoname
  static class FwdEainoname {}

  @ChaosReverseEainoname
  static class RvEainoname {}

  @ChaosWildcardEaifail
  static class WcEaifail {}

  @ChaosWildcardEaisystem
  static class WcEaisystem {}

  @ChaosForwardLatency
  static class FwdLatency {}

  @ChaosReverseLatency(delayMs = 500L)
  static class RvLatencyCustom {}

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
  @DisplayName("DnsEaiTranslator")
  class Eai {

    static Stream<Arguments> tuples() {
      return Stream.of(
          Arguments.of(FwdEaiagain.class, DnsSelector.Kind.FORWARD, EaiErrno.EAI_AGAIN),
          Arguments.of(FwdEainoname.class, DnsSelector.Kind.FORWARD, EaiErrno.EAI_NONAME),
          Arguments.of(RvEainoname.class, DnsSelector.Kind.REVERSE, EaiErrno.EAI_NONAME),
          Arguments.of(WcEaifail.class, DnsSelector.Kind.ANY, EaiErrno.EAI_FAIL),
          Arguments.of(WcEaisystem.class, DnsSelector.Kind.ANY, EaiErrno.EAI_SYSTEM));
    }

    @ParameterizedTest(name = "{0} → ({1}, {2})")
    @MethodSource("tuples")
    void translates(
        final Class<?> fixture, final DnsSelector.Kind expectedKind, final EaiErrno expectedErrno) {
      final DnsRule rule = DnsEaiTranslator.buildRule(pick(fixture));
      assertThat(rule.selector().kind()).isEqualTo(expectedKind);
      assertThat(rule.effect()).isInstanceOf(Effect.EaiFault.class);
      assertThat(((Effect.EaiFault) rule.effect()).errno()).isEqualTo(expectedErrno);
    }

    @Test
    void missingBinding() {
      assertThatThrownBy(() -> DnsEaiTranslator.buildRule(pick(WithPlainNoBinding.class)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@DnsEaiBinding meta-annotation missing");
    }
  }

  @Nested
  @DisplayName("DnsLatencyTranslator")
  class Latency {

    @Test
    void defaultDelay100ms() {
      final DnsRule rule = DnsLatencyTranslator.buildRule(pick(FwdLatency.class));
      assertThat(rule.selector().kind()).isEqualTo(DnsSelector.Kind.FORWARD);
      assertThat(((Effect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void customDelay() {
      final DnsRule rule = DnsLatencyTranslator.buildRule(pick(RvLatencyCustom.class));
      assertThat(rule.selector().kind()).isEqualTo(DnsSelector.Kind.REVERSE);
      assertThat(((Effect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void missingBinding() {
      assertThatThrownBy(() -> DnsLatencyTranslator.buildRule(pick(WithPlainNoBinding.class)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@DnsLatencyBinding meta-annotation missing");
    }
  }
}
