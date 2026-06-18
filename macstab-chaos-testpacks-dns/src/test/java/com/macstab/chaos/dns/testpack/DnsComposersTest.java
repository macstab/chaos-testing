/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.testpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.extension.Severity;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.api.AdvancedDnsChaos;
import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.AddressFamily;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;
import com.macstab.chaos.dns.model.Effect;
import com.macstab.chaos.dns.testpack.composers.DnsBlackholeComposer;
import com.macstab.chaos.dns.testpack.composers.DnsCachePoisoningComposer;
import com.macstab.chaos.dns.testpack.composers.DnsServiceRedirectionComposer;
import com.macstab.chaos.dns.testpack.composers.DnsTemporaryFailureComposer;
import com.macstab.chaos.dns.testpack.composers.DnsTimeoutComposer;
import com.macstab.chaos.dns.testpack.composers.Ipv6OnlyResolutionComposer;
import com.macstab.chaos.dns.testpack.composers.NxDomainComposer;
import com.macstab.chaos.dns.testpack.composers.ReverseDnsFailureComposer;
import com.macstab.chaos.dns.testpack.composers.ShuffledAnswerOrderComposer;

@DisplayName("DNS L2 composers — contract and rule-building")
class DnsComposersTest {

  // ── Annotation contract helpers ──────────────────────────────────────────

  private static ChaosL2 chaosL2(final Class<?> annotationType) {
    final ChaosL2 meta = annotationType.getAnnotation(ChaosL2.class);
    assertThat(meta).as("@ChaosL2 missing on " + annotationType.getSimpleName()).isNotNull();
    return meta;
  }

  // ── Shared mock plumbing ──────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static GenericContainer<?> container() {
    return mock(GenericContainer.class);
  }

  private static RuleHandle handle() {
    return mock(RuleHandle.class);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // NxDomain
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosNxDomain")
  class NxDomainTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosNxDomain.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("NxDomainComposer");
    }

    @Test
    @DisplayName("default host is wildcard")
    void defaultHost() throws Exception {
      final CompositeChaosNxDomain ann =
          fixture(NxDomainFixture.class, CompositeChaosNxDomain.class);
      assertThat(ann.host()).isEqualTo("*");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning EAI_NONAME")
    void describe() {
      final CompositeChaosNxDomain ann =
          fixture(NxDomainFixture.class, CompositeChaosNxDomain.class);
      final List<String> lines = new NxDomainComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("NXDOMAIN")
          .containsIgnoringCase("EAI_NONAME");
    }

    @Test
    @DisplayName("apply() builds EAI_NONAME rule on forward wildcard selector")
    void apply() {
      final CompositeChaosNxDomain ann =
          fixture(NxDomainFixture.class, CompositeChaosNxDomain.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedDnsChaos adv = mock(AdvancedDnsChaos.class);
      final CompositeDnsChaos composite = mock(CompositeDnsChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(DnsRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeDnsChaos> mocked = mockStatic(CompositeDnsChaos.class)) {
        mocked.when(CompositeDnsChaos::standard).thenReturn(composite);
        final List<Object> handles = new NxDomainComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argDnsRule(DnsSelector.anyForward(), EaiErrno.EAI_NONAME));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new NxDomainComposer().removeAll(container(), List.of());
    }

    @CompositeChaosNxDomain
    static class NxDomainFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DnsTimeout
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosDnsTimeout")
  class DnsTimeoutTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default 8000ms")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosDnsTimeout.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("DnsTimeoutComposer");
      final CompositeChaosDnsTimeout ann =
          fixture(TimeoutFixture.class, CompositeChaosDnsTimeout.class);
      assertThat(ann.latencyMs()).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("describe() mentions latency value")
    void describe() {
      final CompositeChaosDnsTimeout ann =
          fixture(TimeoutFixture.class, CompositeChaosDnsTimeout.class);
      final List<String> lines = new DnsTimeoutComposer().describe(ann);
      assertThat(String.join(" ", lines)).contains("8000");
    }

    @Test
    @DisplayName("apply() builds LATENCY rule with correct duration")
    void apply() {
      final CompositeChaosDnsTimeout ann =
          fixture(TimeoutFixture.class, CompositeChaosDnsTimeout.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedDnsChaos adv = mock(AdvancedDnsChaos.class);
      final CompositeDnsChaos composite = mock(CompositeDnsChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(DnsRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeDnsChaos> mocked = mockStatic(CompositeDnsChaos.class)) {
        mocked.when(CompositeDnsChaos::standard).thenReturn(composite);
        final List<Object> handles = new DnsTimeoutComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argLatencyRule(Duration.ofMillis(8_000)));
      }
    }

    @CompositeChaosDnsTimeout
    static class TimeoutFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DnsTemporaryFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosDnsTemporaryFailure")
  class DnsTemporaryFailureTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosDnsTemporaryFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("DnsTemporaryFailureComposer");
    }

    @Test
    @DisplayName("describe() mentions EAI_AGAIN / SERVFAIL")
    void describe() {
      final CompositeChaosDnsTemporaryFailure ann =
          fixture(TempFailFixture.class, CompositeChaosDnsTemporaryFailure.class);
      final List<String> lines = new DnsTemporaryFailureComposer().describe(ann);
      assertThat(String.join(" ", lines)).containsIgnoringCase("EAI_AGAIN");
    }

    @Test
    @DisplayName("apply() builds EAI_AGAIN rule")
    void apply() {
      final CompositeChaosDnsTemporaryFailure ann =
          fixture(TempFailFixture.class, CompositeChaosDnsTemporaryFailure.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedDnsChaos adv = mock(AdvancedDnsChaos.class);
      final CompositeDnsChaos composite = mock(CompositeDnsChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(DnsRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeDnsChaos> mocked = mockStatic(CompositeDnsChaos.class)) {
        mocked.when(CompositeDnsChaos::standard).thenReturn(composite);
        final List<Object> handles = new DnsTemporaryFailureComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argDnsRule(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN));
      }
    }

    @CompositeChaosDnsTemporaryFailure
    static class TempFailFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DnsBlackhole
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosDnsBlackhole")
  class DnsBlackholeTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosDnsBlackhole.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("DnsBlackholeComposer");
    }

    @Test
    @DisplayName("describe() mentions EAI_FAIL")
    void describe() {
      final CompositeChaosDnsBlackhole ann =
          fixture(BlackholeFixture.class, CompositeChaosDnsBlackhole.class);
      final List<String> lines = new DnsBlackholeComposer().describe(ann);
      assertThat(String.join(" ", lines)).containsIgnoringCase("EAI_FAIL");
    }

    @Test
    @DisplayName("apply() builds EAI_FAIL rule")
    void apply() {
      final CompositeChaosDnsBlackhole ann =
          fixture(BlackholeFixture.class, CompositeChaosDnsBlackhole.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedDnsChaos adv = mock(AdvancedDnsChaos.class);
      final CompositeDnsChaos composite = mock(CompositeDnsChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(DnsRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeDnsChaos> mocked = mockStatic(CompositeDnsChaos.class)) {
        mocked.when(CompositeDnsChaos::standard).thenReturn(composite);
        final List<Object> handles = new DnsBlackholeComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argDnsRule(DnsSelector.anyForward(), EaiErrno.EAI_FAIL));
      }
    }

    @CompositeChaosDnsBlackhole
    static class BlackholeFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DnsCachePoisoning
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosDnsCachePoisoning")
  class DnsCachePoisoningTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct FQN + default redirectTo=localhost")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosDnsCachePoisoning.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("DnsCachePoisoningComposer");
      final CompositeChaosDnsCachePoisoning ann =
          fixture(PoisonFixture.class, CompositeChaosDnsCachePoisoning.class);
      assertThat(ann.redirectTo()).isEqualTo("localhost");
    }

    @Test
    @DisplayName("describe() mentions redirectTo target")
    void describe() {
      final CompositeChaosDnsCachePoisoning ann =
          fixture(PoisonFixture.class, CompositeChaosDnsCachePoisoning.class);
      final List<String> lines = new DnsCachePoisoningComposer().describe(ann);
      assertThat(String.join(" ", lines)).contains("localhost");
    }

    @Test
    @DisplayName("apply() builds REWRITE rule pointing to redirectTo")
    void apply() {
      final CompositeChaosDnsCachePoisoning ann =
          fixture(PoisonFixture.class, CompositeChaosDnsCachePoisoning.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedDnsChaos adv = mock(AdvancedDnsChaos.class);
      final CompositeDnsChaos composite = mock(CompositeDnsChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(DnsRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeDnsChaos> mocked = mockStatic(CompositeDnsChaos.class)) {
        mocked.when(CompositeDnsChaos::standard).thenReturn(composite);
        final List<Object> handles = new DnsCachePoisoningComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argRewriteRule("localhost"));
      }
    }

    @CompositeChaosDnsCachePoisoning
    static class PoisonFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DnsServiceRedirection
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosDnsServiceRedirection")
  class DnsServiceRedirectionTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN + default serviceName=invalid-svc")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosDnsServiceRedirection.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("DnsServiceRedirectionComposer");
      final CompositeChaosDnsServiceRedirection ann =
          fixture(SvcRedirFixture.class, CompositeChaosDnsServiceRedirection.class);
      assertThat(ann.serviceName()).isEqualTo("invalid-svc");
    }

    @Test
    @DisplayName("describe() mentions service name")
    void describe() {
      final CompositeChaosDnsServiceRedirection ann =
          fixture(SvcRedirFixture.class, CompositeChaosDnsServiceRedirection.class);
      final List<String> lines = new DnsServiceRedirectionComposer().describe(ann);
      assertThat(String.join(" ", lines)).contains("invalid-svc");
    }

    @CompositeChaosDnsServiceRedirection
    static class SvcRedirFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Ipv6OnlyResolution
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosIpv6OnlyResolution")
  class Ipv6OnlyTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosIpv6OnlyResolution.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("Ipv6OnlyResolutionComposer");
    }

    @Test
    @DisplayName("describe() mentions IPv6")
    void describe() {
      final CompositeChaosIpv6OnlyResolution ann =
          fixture(Ipv6Fixture.class, CompositeChaosIpv6OnlyResolution.class);
      final List<String> lines = new Ipv6OnlyResolutionComposer().describe(ann);
      assertThat(String.join(" ", lines)).containsIgnoringCase("IPv6");
    }

    @Test
    @DisplayName("apply() builds FILTER_FAMILY INET6 rule")
    void apply() {
      final CompositeChaosIpv6OnlyResolution ann =
          fixture(Ipv6Fixture.class, CompositeChaosIpv6OnlyResolution.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedDnsChaos adv = mock(AdvancedDnsChaos.class);
      final CompositeDnsChaos composite = mock(CompositeDnsChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(DnsRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeDnsChaos> mocked = mockStatic(CompositeDnsChaos.class)) {
        mocked.when(CompositeDnsChaos::standard).thenReturn(composite);
        final List<Object> handles = new Ipv6OnlyResolutionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argFilterFamilyRule(AddressFamily.INET6));
      }
    }

    @CompositeChaosIpv6OnlyResolution
    static class Ipv6Fixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ShuffledAnswerOrder
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosShuffledAnswerOrder")
  class ShuffledAnswerOrderTests {

    @Test
    @DisplayName("annotation carries MILD + correct FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosShuffledAnswerOrder.class);
      assertThat(meta.severity()).isEqualTo(Severity.MILD);
      assertThat(meta.composer()).endsWith("ShuffledAnswerOrderComposer");
    }

    @Test
    @DisplayName("describe() mentions shuffle / ordering")
    void describe() {
      final CompositeChaosShuffledAnswerOrder ann =
          fixture(ShuffleFixture.class, CompositeChaosShuffledAnswerOrder.class);
      final List<String> lines = new ShuffledAnswerOrderComposer().describe(ann);
      assertThat(String.join(" ", lines)).containsIgnoringCase("shuffle");
    }

    @Test
    @DisplayName("apply() builds SHUFFLE rule")
    void apply() {
      final CompositeChaosShuffledAnswerOrder ann =
          fixture(ShuffleFixture.class, CompositeChaosShuffledAnswerOrder.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedDnsChaos adv = mock(AdvancedDnsChaos.class);
      final CompositeDnsChaos composite = mock(CompositeDnsChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(DnsRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeDnsChaos> mocked = mockStatic(CompositeDnsChaos.class)) {
        mocked.when(CompositeDnsChaos::standard).thenReturn(composite);
        final List<Object> handles = new ShuffledAnswerOrderComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argShuffleRule());
      }
    }

    @CompositeChaosShuffledAnswerOrder
    static class ShuffleFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ReverseDnsFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosReverseDnsFailure")
  class ReverseDnsFailureTests {

    @Test
    @DisplayName("annotation carries MILD + correct FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosReverseDnsFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.MILD);
      assertThat(meta.composer()).endsWith("ReverseDnsFailureComposer");
    }

    @Test
    @DisplayName("describe() mentions reverse and EAI_NONAME")
    void describe() {
      final CompositeChaosReverseDnsFailure ann =
          fixture(ReverseFixture.class, CompositeChaosReverseDnsFailure.class);
      final List<String> lines = new ReverseDnsFailureComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("reverse")
          .containsIgnoringCase("EAI_NONAME");
    }

    @Test
    @DisplayName("apply() builds EAI_NONAME rule on anyReverse() selector")
    void apply() {
      final CompositeChaosReverseDnsFailure ann =
          fixture(ReverseFixture.class, CompositeChaosReverseDnsFailure.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedDnsChaos adv = mock(AdvancedDnsChaos.class);
      final CompositeDnsChaos composite = mock(CompositeDnsChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(DnsRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeDnsChaos> mocked = mockStatic(CompositeDnsChaos.class)) {
        mocked.when(CompositeDnsChaos::standard).thenReturn(composite);
        final List<Object> handles = new ReverseDnsFailureComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argDnsRule(DnsSelector.anyReverse(), EaiErrno.EAI_NONAME));
      }
    }

    @CompositeChaosReverseDnsFailure
    static class ReverseFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // L2Composer contract — all composers implement the interface
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("all composers implement L2Composer")
  void allComposersImplementInterface() {
    assertThat(new NxDomainComposer()).isInstanceOf(L2Composer.class);
    assertThat(new DnsTimeoutComposer()).isInstanceOf(L2Composer.class);
    assertThat(new DnsTemporaryFailureComposer()).isInstanceOf(L2Composer.class);
    assertThat(new DnsBlackholeComposer()).isInstanceOf(L2Composer.class);
    assertThat(new DnsCachePoisoningComposer()).isInstanceOf(L2Composer.class);
    assertThat(new DnsServiceRedirectionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new Ipv6OnlyResolutionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ShuffledAnswerOrderComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ReverseDnsFailureComposer()).isInstanceOf(L2Composer.class);
  }

  @Test
  @DisplayName("all annotations are repeatable (carry @List container)")
  void allAnnotationsRepeatable() {
    assertThat(CompositeChaosNxDomain.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosDnsTimeout.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosDnsTemporaryFailure.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosDnsBlackhole.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosDnsCachePoisoning.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosDnsServiceRedirection.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosIpv6OnlyResolution.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosShuffledAnswerOrder.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosReverseDnsFailure.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Helpers
  // ═══════════════════════════════════════════════════════════════════════════

  @SuppressWarnings("unchecked")
  private static <A extends java.lang.annotation.Annotation> A fixture(
      final Class<?> holder, final Class<A> type) {
    final A ann = holder.getAnnotation(type);
    assertThat(ann).as(type.getSimpleName() + " missing on " + holder.getSimpleName()).isNotNull();
    return ann;
  }

  private static DnsRule argDnsRule(
      final DnsSelector expectedSelector, final EaiErrno expectedErrno) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> {
          if (!(rule.effect() instanceof Effect.EaiFault ef)) return false;
          return rule.selector().toSelector().equals(expectedSelector.toSelector())
              && ef.errno() == expectedErrno;
        });
  }

  private static DnsRule argLatencyRule(final Duration expectedDelay) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> {
          if (!(rule.effect() instanceof Effect.Latency lat)) return false;
          return lat.delay().equals(expectedDelay);
        });
  }

  private static DnsRule argRewriteRule(final String expectedTarget) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> {
          if (!(rule.effect() instanceof Effect.Rewrite rw)) return false;
          return rw.to().equals(expectedTarget);
        });
  }

  private static DnsRule argFilterFamilyRule(final AddressFamily expectedFamily) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> {
          if (!(rule.effect() instanceof Effect.FilterFamily ff)) return false;
          return ff.family() == expectedFamily;
        });
  }

  private static DnsRule argShuffleRule() {
    return org.mockito.ArgumentMatchers.argThat(rule -> rule.effect() instanceof Effect.Shuffle);
  }
}
