/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack;

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
import com.macstab.chaos.process.CompositeProcessChaos;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessRule;
import com.macstab.chaos.process.model.ProcessSelector;
import com.macstab.chaos.process.testpack.composers.ExecveMemoryDeniedComposer;
import com.macstab.chaos.process.testpack.composers.ExecvePermissionDeniedComposer;
import com.macstab.chaos.process.testpack.composers.ForkBombComposer;
import com.macstab.chaos.process.testpack.composers.GracefulShutdownComposer;
import com.macstab.chaos.process.testpack.composers.HardKillComposer;
import com.macstab.chaos.process.testpack.composers.OomForkComposer;
import com.macstab.chaos.process.testpack.composers.ProcessLimitHitComposer;
import com.macstab.chaos.process.testpack.composers.SignalInterruptionComposer;
import com.macstab.chaos.process.testpack.composers.SpawnFailureComposer;
import com.macstab.chaos.process.testpack.composers.ThreadCreateSlowComposer;
import com.macstab.chaos.process.testpack.composers.ThreadPoolExhaustionComposer;
import com.macstab.chaos.process.testpack.composers.ZombieAccumulationComposer;

@DisplayName("Process L2 composers — contract and rule-building")
class ProcessComposersTest {

  // ── Annotation contract helpers ─────────────────────────────────────────────

  private static ChaosL2 chaosL2(final Class<?> annotationType) {
    final ChaosL2 meta = annotationType.getAnnotation(ChaosL2.class);
    assertThat(meta).as("@ChaosL2 missing on " + annotationType.getSimpleName()).isNotNull();
    return meta;
  }

  // ── Shared mock plumbing ────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static GenericContainer<?> container() {
    return mock(GenericContainer.class);
  }

  private static RuleHandle handle() {
    return mock(RuleHandle.class);
  }

  private static AdvancedProcessChaos mockAdvanced(
      final CompositeProcessChaos composite, final RuleHandle returnHandle) {
    final AdvancedProcessChaos adv = mock(AdvancedProcessChaos.class);
    when(composite.advanced()).thenReturn(adv);
    when(adv.failFork(any(), any(double.class))).thenReturn(returnHandle);
    when(adv.failFork(any(), any(ProcessErrno.class), any(double.class))).thenReturn(returnHandle);
    when(adv.failThreadCreation(any(), any(double.class))).thenReturn(returnHandle);
    when(adv.failWait(any(), any(ProcessErrno.class), any(double.class))).thenReturn(returnHandle);
    when(adv.phantomWait(any(), any(double.class))).thenReturn(returnHandle);
    when(adv.signalInterruptWait(any(), any(double.class))).thenReturn(returnHandle);
    when(adv.failExecPermission(any(), any(double.class))).thenReturn(returnHandle);
    when(adv.failExec(any(), any(ProcessErrno.class), any(double.class))).thenReturn(returnHandle);
    when(adv.failSpawn(any(), any(ProcessErrno.class), any(double.class))).thenReturn(returnHandle);
    when(adv.slowWait(any(), any(Duration.class))).thenReturn(returnHandle);
    when(adv.slowThreadCreation(any(), any(Duration.class))).thenReturn(returnHandle);
    when(adv.apply(any(), any(ProcessRule.class))).thenReturn(returnHandle);
    return adv;
  }

  @SuppressWarnings("unchecked")
  private static <A extends java.lang.annotation.Annotation> A fixture(
      final Class<?> holder, final Class<A> type) {
    final A ann = holder.getAnnotation(type);
    assertThat(ann).as(type.getSimpleName() + " missing on " + holder.getSimpleName()).isNotNull();
    return ann;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ProcessLimitHit
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosProcessLimitHit")
  class ProcessLimitHitTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosProcessLimitHit.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ProcessLimitHitComposer");
    }

    @Test
    @DisplayName("default toxicity is 0.9")
    void defaultToxicity() {
      final CompositeChaosProcessLimitHit ann =
          fixture(ProcessLimitHitFixture.class, CompositeChaosProcessLimitHit.class);
      assertThat(ann.toxicity()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("describe() mentions EAGAIN and fork")
    void describe() {
      final CompositeChaosProcessLimitHit ann =
          fixture(ProcessLimitHitFixture.class, CompositeChaosProcessLimitHit.class);
      final List<String> lines = new ProcessLimitHitComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("EAGAIN").containsIgnoringCase("fork");
    }

    @Test
    @DisplayName("apply() calls failFork with configured toxicity")
    void apply() {
      final CompositeChaosProcessLimitHit ann =
          fixture(ProcessLimitHitFixture.class, CompositeChaosProcessLimitHit.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new ProcessLimitHitComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failFork(eq(c), eq(0.9));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ProcessLimitHitComposer().removeAll(container(), List.of());
    }

    @CompositeChaosProcessLimitHit
    static class ProcessLimitHitFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ThreadPoolExhaustion
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosThreadPoolExhaustion")
  class ThreadPoolExhaustionTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosThreadPoolExhaustion.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ThreadPoolExhaustionComposer");
    }

    @Test
    @DisplayName("default toxicity is 0.9")
    void defaultToxicity() {
      final CompositeChaosThreadPoolExhaustion ann =
          fixture(ThreadPoolExhaustionFixture.class, CompositeChaosThreadPoolExhaustion.class);
      assertThat(ann.toxicity()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("describe() mentions EAGAIN and pthread_create")
    void describe() {
      final CompositeChaosThreadPoolExhaustion ann =
          fixture(ThreadPoolExhaustionFixture.class, CompositeChaosThreadPoolExhaustion.class);
      final List<String> lines = new ThreadPoolExhaustionComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("EAGAIN")
          .containsIgnoringCase("pthread_create");
    }

    @Test
    @DisplayName("apply() calls failThreadCreation with configured toxicity")
    void apply() {
      final CompositeChaosThreadPoolExhaustion ann =
          fixture(ThreadPoolExhaustionFixture.class, CompositeChaosThreadPoolExhaustion.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new ThreadPoolExhaustionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failThreadCreation(eq(c), eq(0.9));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ThreadPoolExhaustionComposer().removeAll(container(), List.of());
    }

    @CompositeChaosThreadPoolExhaustion
    static class ThreadPoolExhaustionFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ForkBomb
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosForkBomb")
  class ForkBombTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct composer FQN + default toxicity 0.95")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosForkBomb.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("ForkBombComposer");
      final CompositeChaosForkBomb ann =
          fixture(ForkBombFixture.class, CompositeChaosForkBomb.class);
      assertThat(ann.toxicity()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("describe() mentions EAGAIN and fork bomb / saturation")
    void describe() {
      final CompositeChaosForkBomb ann =
          fixture(ForkBombFixture.class, CompositeChaosForkBomb.class);
      final List<String> lines = new ForkBombComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("EAGAIN")
          .containsIgnoringCase("fork");
    }

    @Test
    @DisplayName("apply() calls failFork with configured toxicity")
    void apply() {
      final CompositeChaosForkBomb ann =
          fixture(ForkBombFixture.class, CompositeChaosForkBomb.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new ForkBombComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failFork(eq(c), eq(0.95));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ForkBombComposer().removeAll(container(), List.of());
    }

    @CompositeChaosForkBomb
    static class ForkBombFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // OomFork
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosOomFork")
  class OomForkTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN + default toxicity 0.5")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosOomFork.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("OomForkComposer");
      final CompositeChaosOomFork ann =
          fixture(OomForkFixture.class, CompositeChaosOomFork.class);
      assertThat(ann.toxicity()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("describe() mentions ENOMEM and fork")
    void describe() {
      final CompositeChaosOomFork ann =
          fixture(OomForkFixture.class, CompositeChaosOomFork.class);
      final List<String> lines = new OomForkComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("ENOMEM")
          .containsIgnoringCase("fork");
    }

    @Test
    @DisplayName("apply() calls failFork with ENOMEM and configured toxicity")
    void apply() {
      final CompositeChaosOomFork ann =
          fixture(OomForkFixture.class, CompositeChaosOomFork.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new OomForkComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failFork(eq(c), eq(ProcessErrno.ENOMEM), eq(0.5));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new OomForkComposer().removeAll(container(), List.of());
    }

    @CompositeChaosOomFork
    static class OomForkFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HardKill
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosHardKill")
  class HardKillTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct FQN + default toxicity 1.0")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosHardKill.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("HardKillComposer");
      final CompositeChaosHardKill ann =
          fixture(HardKillFixture.class, CompositeChaosHardKill.class);
      assertThat(ann.toxicity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("describe() mentions ESRCH and waitpid")
    void describe() {
      final CompositeChaosHardKill ann =
          fixture(HardKillFixture.class, CompositeChaosHardKill.class);
      final List<String> lines = new HardKillComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("ESRCH")
          .containsIgnoringCase("waitpid");
    }

    @Test
    @DisplayName("apply() calls failWait with ESRCH and configured toxicity")
    void apply() {
      final CompositeChaosHardKill ann =
          fixture(HardKillFixture.class, CompositeChaosHardKill.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new HardKillComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failWait(eq(c), eq(ProcessErrno.ESRCH), eq(1.0));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new HardKillComposer().removeAll(container(), List.of());
    }

    @CompositeChaosHardKill
    static class HardKillFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // GracefulShutdown
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosGracefulShutdown")
  class GracefulShutdownTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default drainMs 5000")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosGracefulShutdown.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("GracefulShutdownComposer");
      final CompositeChaosGracefulShutdown ann =
          fixture(GracefulShutdownFixture.class, CompositeChaosGracefulShutdown.class);
      assertThat(ann.drainMs()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("describe() mentions latency value and waitpid")
    void describe() {
      final CompositeChaosGracefulShutdown ann =
          fixture(GracefulShutdownFixture.class, CompositeChaosGracefulShutdown.class);
      final List<String> lines = new GracefulShutdownComposer().describe(ann);
      assertThat(String.join(" ", lines)).contains("5000").containsIgnoringCase("waitpid");
    }

    @Test
    @DisplayName("apply() calls slowWait with correct duration")
    void apply() {
      final CompositeChaosGracefulShutdown ann =
          fixture(GracefulShutdownFixture.class, CompositeChaosGracefulShutdown.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new GracefulShutdownComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).slowWait(eq(c), eq(Duration.ofMillis(5_000)));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new GracefulShutdownComposer().removeAll(container(), List.of());
    }

    @CompositeChaosGracefulShutdown
    static class GracefulShutdownFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ExecvePermissionDenied
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosExecvePermissionDenied")
  class ExecvePermissionDeniedTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN + default toxicity 1.0")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosExecvePermissionDenied.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ExecvePermissionDeniedComposer");
      final CompositeChaosExecvePermissionDenied ann =
          fixture(ExecvePermFixture.class, CompositeChaosExecvePermissionDenied.class);
      assertThat(ann.toxicity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("describe() mentions EACCES and execve")
    void describe() {
      final CompositeChaosExecvePermissionDenied ann =
          fixture(ExecvePermFixture.class, CompositeChaosExecvePermissionDenied.class);
      final List<String> lines = new ExecvePermissionDeniedComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("EACCES")
          .containsIgnoringCase("execve");
    }

    @Test
    @DisplayName("apply() calls failExecPermission with configured toxicity")
    void apply() {
      final CompositeChaosExecvePermissionDenied ann =
          fixture(ExecvePermFixture.class, CompositeChaosExecvePermissionDenied.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new ExecvePermissionDeniedComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failExecPermission(eq(c), eq(1.0));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ExecvePermissionDeniedComposer().removeAll(container(), List.of());
    }

    @CompositeChaosExecvePermissionDenied
    static class ExecvePermFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SpawnFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSpawnFailure")
  class SpawnFailureTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default toxicity 0.5")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSpawnFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("SpawnFailureComposer");
      final CompositeChaosSpawnFailure ann =
          fixture(SpawnFailureFixture.class, CompositeChaosSpawnFailure.class);
      assertThat(ann.toxicity()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("describe() mentions ENOMEM and posix_spawn")
    void describe() {
      final CompositeChaosSpawnFailure ann =
          fixture(SpawnFailureFixture.class, CompositeChaosSpawnFailure.class);
      final List<String> lines = new SpawnFailureComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("ENOMEM")
          .containsIgnoringCase("posix_spawn");
    }

    @Test
    @DisplayName("apply() calls failSpawn with ENOMEM and configured toxicity")
    void apply() {
      final CompositeChaosSpawnFailure ann =
          fixture(SpawnFailureFixture.class, CompositeChaosSpawnFailure.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new SpawnFailureComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failSpawn(eq(c), eq(ProcessErrno.ENOMEM), eq(0.5));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SpawnFailureComposer().removeAll(container(), List.of());
    }

    @CompositeChaosSpawnFailure
    static class SpawnFailureFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ZombieAccumulation
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosZombieAccumulation")
  class ZombieAccumulationTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default toxicity 0.7")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosZombieAccumulation.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("ZombieAccumulationComposer");
      final CompositeChaosZombieAccumulation ann =
          fixture(ZombieFixture.class, CompositeChaosZombieAccumulation.class);
      assertThat(ann.toxicity()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("describe() mentions ECHILD and zombie")
    void describe() {
      final CompositeChaosZombieAccumulation ann =
          fixture(ZombieFixture.class, CompositeChaosZombieAccumulation.class);
      final List<String> lines = new ZombieAccumulationComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("ECHILD")
          .containsIgnoringCase("zombie");
    }

    @Test
    @DisplayName("apply() calls phantomWait with configured toxicity")
    void apply() {
      final CompositeChaosZombieAccumulation ann =
          fixture(ZombieFixture.class, CompositeChaosZombieAccumulation.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new ZombieAccumulationComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).phantomWait(eq(c), eq(0.7));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ZombieAccumulationComposer().removeAll(container(), List.of());
    }

    @CompositeChaosZombieAccumulation
    static class ZombieFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SignalInterruption
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSignalInterruption")
  class SignalInterruptionTests {

    @Test
    @DisplayName("annotation carries MILD + correct FQN + default toxicity 0.3")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSignalInterruption.class);
      assertThat(meta.severity()).isEqualTo(Severity.MILD);
      assertThat(meta.composer()).endsWith("SignalInterruptionComposer");
      final CompositeChaosSignalInterruption ann =
          fixture(SignalInterruptionFixture.class, CompositeChaosSignalInterruption.class);
      assertThat(ann.toxicity()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("describe() mentions EINTR and SA_RESTART")
    void describe() {
      final CompositeChaosSignalInterruption ann =
          fixture(SignalInterruptionFixture.class, CompositeChaosSignalInterruption.class);
      final List<String> lines = new SignalInterruptionComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("EINTR")
          .containsIgnoringCase("SA_RESTART");
    }

    @Test
    @DisplayName("apply() calls signalInterruptWait with configured toxicity")
    void apply() {
      final CompositeChaosSignalInterruption ann =
          fixture(SignalInterruptionFixture.class, CompositeChaosSignalInterruption.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new SignalInterruptionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).signalInterruptWait(eq(c), eq(0.3));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SignalInterruptionComposer().removeAll(container(), List.of());
    }

    @CompositeChaosSignalInterruption
    static class SignalInterruptionFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ExecveMemoryDenied
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosExecveMemoryDenied")
  class ExecveMemoryDeniedTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN + default toxicity 0.5")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosExecveMemoryDenied.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ExecveMemoryDeniedComposer");
      final CompositeChaosExecveMemoryDenied ann =
          fixture(ExecveMemFixture.class, CompositeChaosExecveMemoryDenied.class);
      assertThat(ann.toxicity()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("describe() mentions ENOMEM and execve")
    void describe() {
      final CompositeChaosExecveMemoryDenied ann =
          fixture(ExecveMemFixture.class, CompositeChaosExecveMemoryDenied.class);
      final List<String> lines = new ExecveMemoryDeniedComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("ENOMEM")
          .containsIgnoringCase("execve");
    }

    @Test
    @DisplayName("apply() calls failExec with ENOMEM and configured toxicity")
    void apply() {
      final CompositeChaosExecveMemoryDenied ann =
          fixture(ExecveMemFixture.class, CompositeChaosExecveMemoryDenied.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new ExecveMemoryDeniedComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).failExec(eq(c), eq(ProcessErrno.ENOMEM), eq(0.5));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ExecveMemoryDeniedComposer().removeAll(container(), List.of());
    }

    @CompositeChaosExecveMemoryDenied
    static class ExecveMemFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ThreadCreateSlow
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosThreadCreateSlow")
  class ThreadCreateSlowTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default latencyMs 200")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosThreadCreateSlow.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("ThreadCreateSlowComposer");
      final CompositeChaosThreadCreateSlow ann =
          fixture(ThreadCreateSlowFixture.class, CompositeChaosThreadCreateSlow.class);
      assertThat(ann.latencyMs()).isEqualTo(200L);
    }

    @Test
    @DisplayName("describe() mentions latency value and pthread_create")
    void describe() {
      final CompositeChaosThreadCreateSlow ann =
          fixture(ThreadCreateSlowFixture.class, CompositeChaosThreadCreateSlow.class);
      final List<String> lines = new ThreadCreateSlowComposer().describe(ann);
      assertThat(String.join(" ", lines)).contains("200").containsIgnoringCase("pthread_create");
    }

    @Test
    @DisplayName("apply() calls slowThreadCreation with correct duration")
    void apply() {
      final CompositeChaosThreadCreateSlow ann =
          fixture(ThreadCreateSlowFixture.class, CompositeChaosThreadCreateSlow.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final CompositeProcessChaos composite = mock(CompositeProcessChaos.class);
      final AdvancedProcessChaos adv = mockAdvanced(composite, h);

      try (final MockedStatic<CompositeProcessChaos> mocked =
          mockStatic(CompositeProcessChaos.class)) {
        mocked.when(CompositeProcessChaos::standard).thenReturn(composite);
        final List<Object> handles = new ThreadCreateSlowComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).slowThreadCreation(eq(c), eq(Duration.ofMillis(200)));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ThreadCreateSlowComposer().removeAll(container(), List.of());
    }

    @CompositeChaosThreadCreateSlow
    static class ThreadCreateSlowFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // L2Composer contract — all composers implement the interface
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("all composers implement L2Composer")
  void allComposersImplementInterface() {
    assertThat(new ProcessLimitHitComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ThreadPoolExhaustionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ForkBombComposer()).isInstanceOf(L2Composer.class);
    assertThat(new OomForkComposer()).isInstanceOf(L2Composer.class);
    assertThat(new HardKillComposer()).isInstanceOf(L2Composer.class);
    assertThat(new GracefulShutdownComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ExecvePermissionDeniedComposer()).isInstanceOf(L2Composer.class);
    assertThat(new SpawnFailureComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ZombieAccumulationComposer()).isInstanceOf(L2Composer.class);
    assertThat(new SignalInterruptionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ExecveMemoryDeniedComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ThreadCreateSlowComposer()).isInstanceOf(L2Composer.class);
  }

  @Test
  @DisplayName("all annotations are repeatable (carry @List container)")
  void allAnnotationsRepeatable() {
    assertThat(
            CompositeChaosProcessLimitHit.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosThreadPoolExhaustion.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosForkBomb.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosOomFork.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosHardKill.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosGracefulShutdown.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosExecvePermissionDenied.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosSpawnFailure.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosZombieAccumulation.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosSignalInterruption.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosExecveMemoryDenied.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosThreadCreateSlow.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
  }
}
