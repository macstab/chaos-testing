/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.testpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.extension.Severity;
import com.macstab.chaos.memory.CompositeMemoryChaos;
import com.macstab.chaos.memory.api.AdvancedMemoryChaos;
import com.macstab.chaos.memory.api.RuleHandle;
import com.macstab.chaos.memory.testpack.composers.HugepageFailureComposer;
import com.macstab.chaos.memory.testpack.composers.JitCompilationFailureComposer;
import com.macstab.chaos.memory.testpack.composers.JvmHeapPressureComposer;
import com.macstab.chaos.memory.testpack.composers.LibraryLoadFailureComposer;
import com.macstab.chaos.memory.testpack.composers.MemoryLeakComposer;
import com.macstab.chaos.memory.testpack.composers.MemoryPressureComposer;
import com.macstab.chaos.memory.testpack.composers.OomKillComposer;
import com.macstab.chaos.memory.testpack.composers.ThreadStackExhaustionComposer;

@DisplayName("Memory L2 composers — contract and rule-building")
class MemoryComposersTest {

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

  @SuppressWarnings("unchecked")
  private static <A extends java.lang.annotation.Annotation> A fixture(
      final Class<?> holder, final Class<A> type) {
    final A ann = holder.getAnnotation(type);
    assertThat(ann).as(type.getSimpleName() + " missing on " + holder.getSimpleName()).isNotNull();
    return ann;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // OomKill
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosOomKill")
  class OomKillTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosOomKill.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("OomKillComposer");
    }

    @Test
    @DisplayName("describe() returns non-empty list mentioning ENOMEM and OOM")
    void describe() {
      final CompositeChaosOomKill ann = fixture(OomKillFixture.class, CompositeChaosOomKill.class);
      final List<String> lines = new OomKillComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("ENOMEM").containsIgnoringCase("OOM");
    }

    @Test
    @DisplayName("apply() calls simulateOomKiller with probability 1.0")
    void apply() {
      final CompositeChaosOomKill ann = fixture(OomKillFixture.class, CompositeChaosOomKill.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedMemoryChaos adv = mock(AdvancedMemoryChaos.class);
      final CompositeMemoryChaos composite = mock(CompositeMemoryChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.simulateOomKiller(eq(c), eq(1.0))).thenReturn(h);

      try (final MockedStatic<CompositeMemoryChaos> mocked = mockStatic(CompositeMemoryChaos.class)) {
        mocked.when(CompositeMemoryChaos::standard).thenReturn(composite);
        final List<Object> handles = new OomKillComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).simulateOomKiller(eq(c), eq(1.0));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new OomKillComposer().removeAll(container(), List.of());
    }

    @CompositeChaosOomKill
    static class OomKillFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // MemoryPressure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosMemoryPressure")
  class MemoryPressureTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default toxicity 0.05")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosMemoryPressure.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("MemoryPressureComposer");
      final CompositeChaosMemoryPressure ann =
          fixture(MemoryPressureFixture.class, CompositeChaosMemoryPressure.class);
      assertThat(ann.toxicity()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("describe() mentions ENOMEM and toxicity value")
    void describe() {
      final CompositeChaosMemoryPressure ann =
          fixture(MemoryPressureFixture.class, CompositeChaosMemoryPressure.class);
      final List<String> lines = new MemoryPressureComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("ENOMEM")
          .contains("0.05");
    }

    @Test
    @DisplayName("apply() calls simulateMemoryPressure with annotation toxicity")
    void apply() {
      final CompositeChaosMemoryPressure ann =
          fixture(MemoryPressureFixture.class, CompositeChaosMemoryPressure.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedMemoryChaos adv = mock(AdvancedMemoryChaos.class);
      final CompositeMemoryChaos composite = mock(CompositeMemoryChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.simulateMemoryPressure(eq(c), eq(0.05))).thenReturn(h);

      try (final MockedStatic<CompositeMemoryChaos> mocked = mockStatic(CompositeMemoryChaos.class)) {
        mocked.when(CompositeMemoryChaos::standard).thenReturn(composite);
        final List<Object> handles = new MemoryPressureComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).simulateMemoryPressure(eq(c), eq(0.05));
      }
    }

    @CompositeChaosMemoryPressure
    static class MemoryPressureFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // MemoryLeak
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosMemoryLeak")
  class MemoryLeakTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default toxicity 0.001")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosMemoryLeak.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("MemoryLeakComposer");
      final CompositeChaosMemoryLeak ann =
          fixture(MemoryLeakFixture.class, CompositeChaosMemoryLeak.class);
      assertThat(ann.toxicity()).isEqualTo(0.001);
    }

    @Test
    @DisplayName("describe() mentions ENOMEM and low rate / soak")
    void describe() {
      final CompositeChaosMemoryLeak ann =
          fixture(MemoryLeakFixture.class, CompositeChaosMemoryLeak.class);
      final List<String> lines = new MemoryLeakComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("ENOMEM")
          .contains("0.001");
    }

    @Test
    @DisplayName("apply() calls failLargeAllocation with annotation toxicity")
    void apply() {
      final CompositeChaosMemoryLeak ann =
          fixture(MemoryLeakFixture.class, CompositeChaosMemoryLeak.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedMemoryChaos adv = mock(AdvancedMemoryChaos.class);
      final CompositeMemoryChaos composite = mock(CompositeMemoryChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.failLargeAllocation(eq(c), eq(0.001))).thenReturn(h);

      try (final MockedStatic<CompositeMemoryChaos> mocked = mockStatic(CompositeMemoryChaos.class)) {
        mocked.when(CompositeMemoryChaos::standard).thenReturn(composite);
        final List<Object> handles = new MemoryLeakComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failLargeAllocation(eq(c), eq(0.001));
      }
    }

    @CompositeChaosMemoryLeak
    static class MemoryLeakFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ThreadStackExhaustion
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosThreadStackExhaustion")
  class ThreadStackExhaustionTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN + default toxicity 0.5")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosThreadStackExhaustion.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ThreadStackExhaustionComposer");
      final CompositeChaosThreadStackExhaustion ann =
          fixture(ThreadStackFixture.class, CompositeChaosThreadStackExhaustion.class);
      assertThat(ann.toxicity()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("describe() mentions thread and ENOMEM")
    void describe() {
      final CompositeChaosThreadStackExhaustion ann =
          fixture(ThreadStackFixture.class, CompositeChaosThreadStackExhaustion.class);
      final List<String> lines = new ThreadStackExhaustionComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("thread")
          .containsIgnoringCase("ENOMEM");
    }

    @Test
    @DisplayName("apply() calls failThreadCreation with annotation toxicity")
    void apply() {
      final CompositeChaosThreadStackExhaustion ann =
          fixture(ThreadStackFixture.class, CompositeChaosThreadStackExhaustion.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedMemoryChaos adv = mock(AdvancedMemoryChaos.class);
      final CompositeMemoryChaos composite = mock(CompositeMemoryChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.failThreadCreation(eq(c), eq(0.5))).thenReturn(h);

      try (final MockedStatic<CompositeMemoryChaos> mocked = mockStatic(CompositeMemoryChaos.class)) {
        mocked.when(CompositeMemoryChaos::standard).thenReturn(composite);
        final List<Object> handles = new ThreadStackExhaustionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failThreadCreation(eq(c), eq(0.5));
      }
    }

    @CompositeChaosThreadStackExhaustion
    static class ThreadStackFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // JitCompilationFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosJitCompilationFailure")
  class JitCompilationFailureTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN + default toxicity 0.8")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosJitCompilationFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("JitCompilationFailureComposer");
      final CompositeChaosJitCompilationFailure ann =
          fixture(JitFixture.class, CompositeChaosJitCompilationFailure.class);
      assertThat(ann.toxicity()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("describe() mentions EACCES and JIT")
    void describe() {
      final CompositeChaosJitCompilationFailure ann =
          fixture(JitFixture.class, CompositeChaosJitCompilationFailure.class);
      final List<String> lines = new JitCompilationFailureComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("EACCES")
          .containsIgnoringCase("JIT");
    }

    @Test
    @DisplayName("apply() calls failJitCompilation with annotation toxicity")
    void apply() {
      final CompositeChaosJitCompilationFailure ann =
          fixture(JitFixture.class, CompositeChaosJitCompilationFailure.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedMemoryChaos adv = mock(AdvancedMemoryChaos.class);
      final CompositeMemoryChaos composite = mock(CompositeMemoryChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.failJitCompilation(eq(c), eq(0.8))).thenReturn(h);

      try (final MockedStatic<CompositeMemoryChaos> mocked = mockStatic(CompositeMemoryChaos.class)) {
        mocked.when(CompositeMemoryChaos::standard).thenReturn(composite);
        final List<Object> handles = new JitCompilationFailureComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failJitCompilation(eq(c), eq(0.8));
      }
    }

    @CompositeChaosJitCompilationFailure
    static class JitFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // LibraryLoadFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosLibraryLoadFailure")
  class LibraryLoadFailureTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosLibraryLoadFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("LibraryLoadFailureComposer");
    }

    @Test
    @DisplayName("describe() mentions EBADF and library/dlopen")
    void describe() {
      final CompositeChaosLibraryLoadFailure ann =
          fixture(LibraryLoadFixture.class, CompositeChaosLibraryLoadFailure.class);
      final List<String> lines = new LibraryLoadFailureComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("EBADF")
          .containsIgnoringCase("dlopen");
    }

    @Test
    @DisplayName("apply() calls failLibraryLoad with probability 1.0")
    void apply() {
      final CompositeChaosLibraryLoadFailure ann =
          fixture(LibraryLoadFixture.class, CompositeChaosLibraryLoadFailure.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedMemoryChaos adv = mock(AdvancedMemoryChaos.class);
      final CompositeMemoryChaos composite = mock(CompositeMemoryChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.failLibraryLoad(eq(c), eq(1.0))).thenReturn(h);

      try (final MockedStatic<CompositeMemoryChaos> mocked = mockStatic(CompositeMemoryChaos.class)) {
        mocked.when(CompositeMemoryChaos::standard).thenReturn(composite);
        final List<Object> handles = new LibraryLoadFailureComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failLibraryLoad(eq(c), eq(1.0));
      }
    }

    @CompositeChaosLibraryLoadFailure
    static class LibraryLoadFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HugepageFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosHugepageFailure")
  class HugepageFailureTests {

    @Test
    @DisplayName("annotation carries MILD + correct FQN + default toxicity 0.3")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosHugepageFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.MILD);
      assertThat(meta.composer()).endsWith("HugepageFailureComposer");
      final CompositeChaosHugepageFailure ann =
          fixture(HugepageFixture.class, CompositeChaosHugepageFailure.class);
      assertThat(ann.toxicity()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("describe() mentions madvise and ENOMEM")
    void describe() {
      final CompositeChaosHugepageFailure ann =
          fixture(HugepageFixture.class, CompositeChaosHugepageFailure.class);
      final List<String> lines = new HugepageFailureComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("madvise")
          .containsIgnoringCase("ENOMEM");
    }

    @Test
    @DisplayName("apply() calls failPagePurge with annotation toxicity")
    void apply() {
      final CompositeChaosHugepageFailure ann =
          fixture(HugepageFixture.class, CompositeChaosHugepageFailure.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedMemoryChaos adv = mock(AdvancedMemoryChaos.class);
      final CompositeMemoryChaos composite = mock(CompositeMemoryChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.failPagePurge(eq(c), eq(0.3))).thenReturn(h);

      try (final MockedStatic<CompositeMemoryChaos> mocked = mockStatic(CompositeMemoryChaos.class)) {
        mocked.when(CompositeMemoryChaos::standard).thenReturn(composite);
        final List<Object> handles = new HugepageFailureComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failPagePurge(eq(c), eq(0.3));
      }
    }

    @CompositeChaosHugepageFailure
    static class HugepageFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // JvmHeapPressure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosJvmHeapPressure")
  class JvmHeapPressureTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default toxicity 0.1")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosJvmHeapPressure.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("JvmHeapPressureComposer");
      final CompositeChaosJvmHeapPressure ann =
          fixture(JvmHeapFixture.class, CompositeChaosJvmHeapPressure.class);
      assertThat(ann.toxicity()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("describe() mentions ENOMEM and JVM / heap")
    void describe() {
      final CompositeChaosJvmHeapPressure ann =
          fixture(JvmHeapFixture.class, CompositeChaosJvmHeapPressure.class);
      final List<String> lines = new JvmHeapPressureComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("ENOMEM")
          .containsIgnoringCase("JVM");
    }

    @Test
    @DisplayName("apply() calls failHeapAllocation with annotation toxicity")
    void apply() {
      final CompositeChaosJvmHeapPressure ann =
          fixture(JvmHeapFixture.class, CompositeChaosJvmHeapPressure.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedMemoryChaos adv = mock(AdvancedMemoryChaos.class);
      final CompositeMemoryChaos composite = mock(CompositeMemoryChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.failHeapAllocation(eq(c), eq(0.1))).thenReturn(h);

      try (final MockedStatic<CompositeMemoryChaos> mocked = mockStatic(CompositeMemoryChaos.class)) {
        mocked.when(CompositeMemoryChaos::standard).thenReturn(composite);
        final List<Object> handles = new JvmHeapPressureComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failHeapAllocation(eq(c), eq(0.1));
      }
    }

    @CompositeChaosJvmHeapPressure
    static class JvmHeapFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // L2Composer contract — all composers implement the interface
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("all composers implement L2Composer")
  void allComposersImplementInterface() {
    assertThat(new OomKillComposer()).isInstanceOf(L2Composer.class);
    assertThat(new MemoryPressureComposer()).isInstanceOf(L2Composer.class);
    assertThat(new MemoryLeakComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ThreadStackExhaustionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new JitCompilationFailureComposer()).isInstanceOf(L2Composer.class);
    assertThat(new LibraryLoadFailureComposer()).isInstanceOf(L2Composer.class);
    assertThat(new HugepageFailureComposer()).isInstanceOf(L2Composer.class);
    assertThat(new JvmHeapPressureComposer()).isInstanceOf(L2Composer.class);
  }

  @Test
  @DisplayName("all annotations are repeatable (carry @List container)")
  void allAnnotationsRepeatable() {
    assertThat(CompositeChaosOomKill.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosMemoryPressure.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosMemoryLeak.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosThreadStackExhaustion.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosJitCompilationFailure.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosLibraryLoadFailure.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosHugepageFailure.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosJvmHeapPressure.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
  }
}
