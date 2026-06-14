/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.testpack;

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
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.api.AdvancedTimeChaos;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeEffect;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;
import com.macstab.chaos.time.testpack.composers.ClockSkewComposer;
import com.macstab.chaos.time.testpack.composers.FrozenClockComposer;
import com.macstab.chaos.time.testpack.composers.LeapSecondComposer;
import com.macstab.chaos.time.testpack.composers.NanosleepInterruptionComposer;
import com.macstab.chaos.time.testpack.composers.SlowMonotonicComposer;
import com.macstab.chaos.time.testpack.composers.TimeTravelComposer;
import com.macstab.chaos.time.testpack.composers.TimerCascadeComposer;

@DisplayName("Time L2 composers — contract and rule-building")
class TimeComposersTest {

  // ── Annotation contract helpers ──────────────────────────────────────────

  private static ChaosL2 chaosL2(final Class<?> annotationType) {
    final ChaosL2 meta = annotationType.getAnnotation(ChaosL2.class);
    assertThat(meta).as("@ChaosL2 missing on " + annotationType.getSimpleName()).isNotNull();
    return meta;
  }

  // ── Shared mock plumbing ─────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static GenericContainer<?> container() {
    return mock(GenericContainer.class);
  }

  private static RuleHandle handle() {
    return mock(RuleHandle.class);
  }

  private static CompositeTimeChaos mockComposite(
      final AdvancedTimeChaos adv, final RuleHandle handle) {
    final CompositeTimeChaos composite = mock(CompositeTimeChaos.class);
    when(composite.advanced()).thenReturn(adv);
    when(adv.apply(any(), any(TimeRule.class))).thenReturn(handle);
    return composite;
  }

  // ── TimeRule argument matchers ────────────────────────────────────────────

  private static TimeRule argOffsetRule(final TimeClock clock, final long expectedMs) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> {
          if (rule.clock().isEmpty() || rule.clock().get() != clock) {
            return false;
          }
          if (!(rule.effect() instanceof TimeEffect.Offset off)) {
            return false;
          }
          return off.delta().toMillis() == expectedMs;
        });
  }

  private static TimeRule argOffsetRuleNoClock(final long expectedMs) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> {
          if (rule.clock().isPresent()) {
            return false;
          }
          if (!(rule.effect() instanceof TimeEffect.Offset off)) {
            return false;
          }
          return off.delta().toMillis() == expectedMs;
        });
  }

  private static TimeRule argErrnoRule(
      final TimeSelector selector, final TimeErrno errno, final double probability) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> {
          if (rule.selector() != selector) {
            return false;
          }
          if (!(rule.effect() instanceof TimeEffect.ErrnoFault ef)) {
            return false;
          }
          return ef.errno() == errno && Double.compare(ef.probability(), probability) == 0;
        });
  }

  private static TimeRule argLatencyRule(
      final TimeSelector selector, final Duration expectedDelay) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> {
          if (rule.selector() != selector) {
            return false;
          }
          if (!(rule.effect() instanceof TimeEffect.Latency lat)) {
            return false;
          }
          return lat.delay().equals(expectedDelay);
        });
  }

  private static TimeRule argNegativeOffsetRuleNoClock() {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> {
          if (rule.clock().isPresent()) {
            return false;
          }
          if (!(rule.effect() instanceof TimeEffect.Offset off)) {
            return false;
          }
          return off.delta().isNegative();
        });
  }

  @SuppressWarnings("unchecked")
  private static <A extends java.lang.annotation.Annotation> A fixture(
      final Class<?> holder, final Class<A> type) {
    final A ann = holder.getAnnotation(type);
    assertThat(ann).as(type.getSimpleName() + " missing on " + holder.getSimpleName()).isNotNull();
    return ann;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosClockSkew
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosClockSkew")
  class ClockSkewTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosClockSkew.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("ClockSkewComposer");
    }

    @Test
    @DisplayName("default skewMs is 500")
    void defaultSkewMs() {
      final CompositeChaosClockSkew ann =
          fixture(ClockSkewFixture.class, CompositeChaosClockSkew.class);
      assertThat(ann.skewMs()).isEqualTo(500L);
    }

    @Test
    @DisplayName("describe() mentions REALTIME and skewMs")
    void describe() {
      final CompositeChaosClockSkew ann =
          fixture(ClockSkewFixture.class, CompositeChaosClockSkew.class);
      final List<String> lines = new ClockSkewComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("REALTIME");
      assertThat(String.join(" ", lines)).contains("500");
    }

    @Test
    @DisplayName("apply() builds OFFSET +500 ms rule on CLOCK_REALTIME")
    void apply() {
      final CompositeChaosClockSkew ann =
          fixture(ClockSkewFixture.class, CompositeChaosClockSkew.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedTimeChaos adv = mock(AdvancedTimeChaos.class);
      final CompositeTimeChaos composite = mockComposite(adv, h);

      try (final MockedStatic<CompositeTimeChaos> mocked = mockStatic(CompositeTimeChaos.class)) {
        mocked.when(CompositeTimeChaos::standard).thenReturn(composite);
        final List<Object> handles = new ClockSkewComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argOffsetRule(TimeClock.REALTIME, 500L));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ClockSkewComposer().removeAll(container(), List.of());
    }

    @CompositeChaosClockSkew
    static class ClockSkewFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosLeapSecond
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosLeapSecond")
  class LeapSecondTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosLeapSecond.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("LeapSecondComposer");
    }

    @Test
    @DisplayName("describe() mentions leap-second and REALTIME")
    void describe() {
      final CompositeChaosLeapSecond ann =
          fixture(LeapSecondFixture.class, CompositeChaosLeapSecond.class);
      final List<String> lines = new LeapSecondComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("leap");
      assertThat(String.join(" ", lines)).contains("1000");
    }

    @Test
    @DisplayName("apply() builds OFFSET +1000 ms rule on CLOCK_REALTIME")
    void apply() {
      final CompositeChaosLeapSecond ann =
          fixture(LeapSecondFixture.class, CompositeChaosLeapSecond.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedTimeChaos adv = mock(AdvancedTimeChaos.class);
      final CompositeTimeChaos composite = mockComposite(adv, h);

      try (final MockedStatic<CompositeTimeChaos> mocked = mockStatic(CompositeTimeChaos.class)) {
        mocked.when(CompositeTimeChaos::standard).thenReturn(composite);
        final List<Object> handles = new LeapSecondComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argOffsetRule(TimeClock.REALTIME, 1_000L));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new LeapSecondComposer().removeAll(container(), List.of());
    }

    @CompositeChaosLeapSecond
    static class LeapSecondFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosSlowMonotonic
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSlowMonotonic")
  class SlowMonotonicTests {

    @Test
    @DisplayName("annotation carries MILD + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSlowMonotonic.class);
      assertThat(meta.severity()).isEqualTo(Severity.MILD);
      assertThat(meta.composer()).endsWith("SlowMonotonicComposer");
    }

    @Test
    @DisplayName("default skewMs is 250")
    void defaultSkewMs() {
      final CompositeChaosSlowMonotonic ann =
          fixture(SlowMonotonicFixture.class, CompositeChaosSlowMonotonic.class);
      assertThat(ann.skewMs()).isEqualTo(250L);
    }

    @Test
    @DisplayName("describe() mentions MONOTONIC and skewMs")
    void describe() {
      final CompositeChaosSlowMonotonic ann =
          fixture(SlowMonotonicFixture.class, CompositeChaosSlowMonotonic.class);
      final List<String> lines = new SlowMonotonicComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("MONOTONIC");
      assertThat(String.join(" ", lines)).contains("250");
    }

    @Test
    @DisplayName("apply() builds OFFSET -250 ms rule on CLOCK_MONOTONIC")
    void apply() {
      final CompositeChaosSlowMonotonic ann =
          fixture(SlowMonotonicFixture.class, CompositeChaosSlowMonotonic.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedTimeChaos adv = mock(AdvancedTimeChaos.class);
      final CompositeTimeChaos composite = mockComposite(adv, h);

      try (final MockedStatic<CompositeTimeChaos> mocked = mockStatic(CompositeTimeChaos.class)) {
        mocked.when(CompositeTimeChaos::standard).thenReturn(composite);
        final List<Object> handles = new SlowMonotonicComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argOffsetRule(TimeClock.MONOTONIC, -250L));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SlowMonotonicComposer().removeAll(container(), List.of());
    }

    @CompositeChaosSlowMonotonic
    static class SlowMonotonicFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosFrozenClock
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosFrozenClock")
  class FrozenClockTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosFrozenClock.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("FrozenClockComposer");
    }

    @Test
    @DisplayName("describe() mentions frozen and epoch")
    void describe() {
      final CompositeChaosFrozenClock ann =
          fixture(FrozenClockFixture.class, CompositeChaosFrozenClock.class);
      final List<String> lines = new FrozenClockComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("frozen");
    }

    @Test
    @DisplayName("apply() builds negative all-clocks OFFSET rule (no clock qualifier)")
    void apply() {
      final CompositeChaosFrozenClock ann =
          fixture(FrozenClockFixture.class, CompositeChaosFrozenClock.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedTimeChaos adv = mock(AdvancedTimeChaos.class);
      final CompositeTimeChaos composite = mockComposite(adv, h);

      try (final MockedStatic<CompositeTimeChaos> mocked = mockStatic(CompositeTimeChaos.class)) {
        mocked.when(CompositeTimeChaos::standard).thenReturn(composite);
        final List<Object> handles = new FrozenClockComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        // The rule must use CLOCK_GETTIME with no clock qualifier and a large negative offset.
        verify(adv).apply(eq(c), argNegativeOffsetRuleNoClock());
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new FrozenClockComposer().removeAll(container(), List.of());
    }

    @CompositeChaosFrozenClock
    static class FrozenClockFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosTimeTravel
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosTimeTravel")
  class TimeTravelTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosTimeTravel.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("TimeTravelComposer");
    }

    @Test
    @DisplayName("default skewMs is 3_600_000")
    void defaultSkewMs() {
      final CompositeChaosTimeTravel ann =
          fixture(TimeTravelFixture.class, CompositeChaosTimeTravel.class);
      assertThat(ann.skewMs()).isEqualTo(3_600_000L);
    }

    @Test
    @DisplayName("describe() mentions backward jump and REALTIME")
    void describe() {
      final CompositeChaosTimeTravel ann =
          fixture(TimeTravelFixture.class, CompositeChaosTimeTravel.class);
      final List<String> lines = new TimeTravelComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("backward");
      assertThat(String.join(" ", lines)).containsIgnoringCase("REALTIME");
    }

    @Test
    @DisplayName("apply() builds OFFSET -3_600_000 ms rule on CLOCK_REALTIME")
    void apply() {
      final CompositeChaosTimeTravel ann =
          fixture(TimeTravelFixture.class, CompositeChaosTimeTravel.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedTimeChaos adv = mock(AdvancedTimeChaos.class);
      final CompositeTimeChaos composite = mockComposite(adv, h);

      try (final MockedStatic<CompositeTimeChaos> mocked = mockStatic(CompositeTimeChaos.class)) {
        mocked.when(CompositeTimeChaos::standard).thenReturn(composite);
        final List<Object> handles = new TimeTravelComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argOffsetRule(TimeClock.REALTIME, -3_600_000L));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new TimeTravelComposer().removeAll(container(), List.of());
    }

    @CompositeChaosTimeTravel
    static class TimeTravelFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosNanosleepInterruption
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosNanosleepInterruption")
  class NanosleepInterruptionTests {

    @Test
    @DisplayName("annotation carries MILD + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosNanosleepInterruption.class);
      assertThat(meta.severity()).isEqualTo(Severity.MILD);
      assertThat(meta.composer()).endsWith("NanosleepInterruptionComposer");
    }

    @Test
    @DisplayName("default toxicity is 0.4")
    void defaultToxicity() {
      final CompositeChaosNanosleepInterruption ann =
          fixture(NanosleepFixture.class, CompositeChaosNanosleepInterruption.class);
      assertThat(ann.toxicity()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("describe() mentions EINTR and toxicity")
    void describe() {
      final CompositeChaosNanosleepInterruption ann =
          fixture(NanosleepFixture.class, CompositeChaosNanosleepInterruption.class);
      final List<String> lines = new NanosleepInterruptionComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("EINTR");
      assertThat(String.join(" ", lines)).contains("0.4");
    }

    @Test
    @DisplayName("apply() builds EINTR rule on NANOSLEEP at toxicity 0.4")
    void apply() {
      final CompositeChaosNanosleepInterruption ann =
          fixture(NanosleepFixture.class, CompositeChaosNanosleepInterruption.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedTimeChaos adv = mock(AdvancedTimeChaos.class);
      final CompositeTimeChaos composite = mockComposite(adv, h);

      try (final MockedStatic<CompositeTimeChaos> mocked = mockStatic(CompositeTimeChaos.class)) {
        mocked.when(CompositeTimeChaos::standard).thenReturn(composite);
        final List<Object> handles = new NanosleepInterruptionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argErrnoRule(TimeSelector.NANOSLEEP, TimeErrno.EINTR, 0.4));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new NanosleepInterruptionComposer().removeAll(container(), List.of());
    }

    @CompositeChaosNanosleepInterruption
    static class NanosleepFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosTimerCascade
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosTimerCascade")
  class TimerCascadeTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosTimerCascade.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("TimerCascadeComposer");
    }

    @Test
    @DisplayName("default latencyMs is 100")
    void defaultLatencyMs() {
      final CompositeChaosTimerCascade ann =
          fixture(TimerCascadeFixture.class, CompositeChaosTimerCascade.class);
      assertThat(ann.latencyMs()).isEqualTo(100L);
    }

    @Test
    @DisplayName("describe() mentions latency and cascade")
    void describe() {
      final CompositeChaosTimerCascade ann =
          fixture(TimerCascadeFixture.class, CompositeChaosTimerCascade.class);
      final List<String> lines = new TimerCascadeComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("latency");
      assertThat(String.join(" ", lines)).contains("100");
    }

    @Test
    @DisplayName("apply() builds LATENCY 100 ms rule on NANOSLEEP")
    void apply() {
      final CompositeChaosTimerCascade ann =
          fixture(TimerCascadeFixture.class, CompositeChaosTimerCascade.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedTimeChaos adv = mock(AdvancedTimeChaos.class);
      final CompositeTimeChaos composite = mockComposite(adv, h);

      try (final MockedStatic<CompositeTimeChaos> mocked = mockStatic(CompositeTimeChaos.class)) {
        mocked.when(CompositeTimeChaos::standard).thenReturn(composite);
        final List<Object> handles = new TimerCascadeComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argLatencyRule(TimeSelector.NANOSLEEP, Duration.ofMillis(100)));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new TimerCascadeComposer().removeAll(container(), List.of());
    }

    @CompositeChaosTimerCascade
    static class TimerCascadeFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Cross-cutting: interface + @Repeatable contract
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("all composers implement L2Composer")
  void allComposersImplementInterface() {
    assertThat(new ClockSkewComposer()).isInstanceOf(L2Composer.class);
    assertThat(new LeapSecondComposer()).isInstanceOf(L2Composer.class);
    assertThat(new SlowMonotonicComposer()).isInstanceOf(L2Composer.class);
    assertThat(new FrozenClockComposer()).isInstanceOf(L2Composer.class);
    assertThat(new TimeTravelComposer()).isInstanceOf(L2Composer.class);
    assertThat(new NanosleepInterruptionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new TimerCascadeComposer()).isInstanceOf(L2Composer.class);
  }

  @Test
  @DisplayName("all annotations are repeatable (carry @List container)")
  void allAnnotationsRepeatable() {
    assertThat(CompositeChaosClockSkew.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosLeapSecond.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosSlowMonotonic.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosFrozenClock.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosTimeTravel.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosNanosleepInterruption.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosTimerCascade.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
  }

  @Test
  @DisplayName("all annotations carry @ChaosL2 meta-annotation")
  void allAnnotationsCarryChaosL2() {
    assertThat(chaosL2(CompositeChaosClockSkew.class)).isNotNull();
    assertThat(chaosL2(CompositeChaosLeapSecond.class)).isNotNull();
    assertThat(chaosL2(CompositeChaosSlowMonotonic.class)).isNotNull();
    assertThat(chaosL2(CompositeChaosFrozenClock.class)).isNotNull();
    assertThat(chaosL2(CompositeChaosTimeTravel.class)).isNotNull();
    assertThat(chaosL2(CompositeChaosNanosleepInterruption.class)).isNotNull();
    assertThat(chaosL2(CompositeChaosTimerCascade.class)).isNotNull();
  }
}
