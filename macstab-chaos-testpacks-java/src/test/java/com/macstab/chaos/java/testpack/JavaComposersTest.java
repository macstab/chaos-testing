/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.annotation.Repeatable;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.extension.Severity;
import com.macstab.chaos.java.testpack.composers.BlockingQueueOverflowComposer;
import com.macstab.chaos.java.testpack.composers.ClassLoadFailureComposer;
import com.macstab.chaos.java.testpack.composers.ClockSkewInProcessComposer;
import com.macstab.chaos.java.testpack.composers.CodeCachePressureComposer;
import com.macstab.chaos.java.testpack.composers.CompletableFutureCancellationComposer;
import com.macstab.chaos.java.testpack.composers.ConnectionPoolExhaustionComposer;
import com.macstab.chaos.java.testpack.composers.DeadlockComposer;
import com.macstab.chaos.java.testpack.composers.DirectBufferLeakComposer;
import com.macstab.chaos.java.testpack.composers.FailingShutdownHookComposer;
import com.macstab.chaos.java.testpack.composers.FinalizerBacklogComposer;
import com.macstab.chaos.java.testpack.composers.GcPauseComposer;
import com.macstab.chaos.java.testpack.composers.HttpClientCascadeComposer;
import com.macstab.chaos.java.testpack.composers.HttpServerError5xxComposer;
import com.macstab.chaos.java.testpack.composers.JdbcRollbackFailureComposer;
import com.macstab.chaos.java.testpack.composers.JmxInvocationStormComposer;
import com.macstab.chaos.java.testpack.composers.JndiInjectionComposer;
import com.macstab.chaos.java.testpack.composers.MetaspacePressureComposer;
import com.macstab.chaos.java.testpack.composers.MethodExceptionInjectionComposer;
import com.macstab.chaos.java.testpack.composers.MethodReturnCorruptionComposer;
import com.macstab.chaos.java.testpack.composers.MonitorContentionComposer;
import com.macstab.chaos.java.testpack.composers.NativeLibraryLoadFailureComposer;
import com.macstab.chaos.java.testpack.composers.ReferenceQueueFloodComposer;
import com.macstab.chaos.java.testpack.composers.SafepointStormComposer;
import com.macstab.chaos.java.testpack.composers.ScheduledTaskMissedComposer;
import com.macstab.chaos.java.testpack.composers.ShutdownHookHangComposer;
import com.macstab.chaos.java.testpack.composers.SlowQueryComposer;
import com.macstab.chaos.java.testpack.composers.SpuriousWakeupComposer;
import com.macstab.chaos.java.testpack.composers.SslHandshakeFailureComposer;
import com.macstab.chaos.java.testpack.composers.StringInternStormComposer;
import com.macstab.chaos.java.testpack.composers.ThreadLocalLeakComposer;
import com.macstab.chaos.java.testpack.composers.ThreadStarvationComposer;
import com.macstab.chaos.java.testpack.composers.UnsafeDeserializationComposer;
import com.macstab.chaos.java.testpack.composers.VirtualThreadPinningComposer;
import com.macstab.chaos.java.testpack.composers.ZipBombComposer;

/**
 * Contract tests for all 34 Java L2 composer and annotation pairs.
 *
 * <p>Each nested class covers:
 * <ol>
 *   <li>Annotation carries the correct {@code @ChaosL2} severity and composer FQN suffix.</li>
 *   <li>{@code describe()} returns a non-empty list.</li>
 *   <li>{@code removeAll()} with an empty list does nothing (no exception).</li>
 *   <li>The annotation type is {@code @Repeatable}.</li>
 *   <li>The composer class implements {@link L2Composer}.</li>
 * </ol>
 *
 * <p>{@code apply()} is not tested here because it requires a running container with a live JVM
 * chaos agent attached — that belongs to integration tests.
 */
@DisplayName("Java L2 composers — contract tests")
class JavaComposersTest {

  // ── helpers ──────────────────────────────────────────────────────────────

  private static ChaosL2 chaosL2(final Class<?> annotationType) {
    final ChaosL2 meta = annotationType.getAnnotation(ChaosL2.class);
    assertThat(meta).as("@ChaosL2 missing on " + annotationType.getSimpleName()).isNotNull();
    return meta;
  }

  @SuppressWarnings("unchecked")
  private static GenericContainer<?> container() {
    return mock(GenericContainer.class);
  }

  private static <A extends java.lang.annotation.Annotation> A fixture(
      final Class<?> holder, final Class<A> type) {
    final A ann = holder.getAnnotation(type);
    assertThat(ann).as(type.getSimpleName() + " missing on " + holder.getSimpleName()).isNotNull();
    return ann;
  }

  // ── fixture annotations ──────────────────────────────────────────────────
  // These inner classes carry the annotations so fixture() can read them.

  @CompositeChaosGcPause
  static class GcPauseFixture {}

  @CompositeChaosDeadlock
  static class DeadlockFixture {}

  @CompositeChaosThreadStarvation
  static class ThreadStarvationFixture {}

  @CompositeChaosVirtualThreadPinning
  static class VirtualThreadPinningFixture {}

  @CompositeChaosSafepointStorm
  static class SafepointStormFixture {}

  @CompositeChaosMonitorContention
  static class MonitorContentionFixture {}

  @CompositeChaosMetaspacePressure
  static class MetaspacePressureFixture {}

  @CompositeChaosDirectBufferLeak
  static class DirectBufferLeakFixture {}

  @CompositeChaosCodeCachePressure
  static class CodeCachePressureFixture {}

  @CompositeChaosFinalizerBacklog
  static class FinalizerBacklogFixture {}

  @CompositeChaosStringInternStorm
  static class StringInternStormFixture {}

  @CompositeChaosThreadLocalLeak
  static class ThreadLocalLeakFixture {}

  @CompositeChaosReferenceQueueFlood
  static class ReferenceQueueFloodFixture {}

  @CompositeChaosSpuriousWakeup
  static class SpuriousWakeupFixture {}

  @CompositeChaosZipBomb
  static class ZipBombFixture {}

  @CompositeChaosConnectionPoolExhaustion
  static class ConnectionPoolExhaustionFixture {}

  @CompositeChaosSlowQuery
  static class SlowQueryFixture {}

  @CompositeChaosJdbcRollbackFailure
  static class JdbcRollbackFailureFixture {}

  @CompositeChaosHttpClientCascade
  static class HttpClientCascadeFixture {}

  @CompositeChaosHttpServerError5xx
  static class HttpServerError5xxFixture {}

  @CompositeChaosShutdownHookHang
  static class ShutdownHookHangFixture {}

  @CompositeChaosScheduledTaskMissed
  static class ScheduledTaskMissedFixture {}

  @CompositeChaosBlockingQueueOverflow
  static class BlockingQueueOverflowFixture {}

  @CompositeChaosCompletableFutureCancellation
  static class CompletableFutureCancellationFixture {}

  @CompositeChaosClassLoadFailure
  static class ClassLoadFailureFixture {}

  @CompositeChaosJndiInjection
  static class JndiInjectionFixture {}

  @CompositeChaosUnsafeDeserialization
  static class UnsafeDeserializationFixture {}

  @CompositeChaosNativeLibraryLoadFailure
  static class NativeLibraryLoadFailureFixture {}

  @CompositeChaosJmxInvocationStorm
  static class JmxInvocationStormFixture {}

  @CompositeChaosSslHandshakeFailure
  static class SslHandshakeFailureFixture {}

  @CompositeChaosMethodExceptionInjection
  static class MethodExceptionInjectionFixture {}

  @CompositeChaosMethodReturnCorruption
  static class MethodReturnCorruptionFixture {}

  @CompositeChaosClockSkewInProcess
  static class ClockSkewInProcessFixture {}

  @CompositeChaosFailingShutdownHook
  static class FailingShutdownHookFixture {}

  // ═══════════════════════════════════════════════════════════════════════════
  // GcPause
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosGcPause")
  class GcPauseTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosGcPause.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("GcPauseComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosGcPause.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(GcPauseComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosGcPause ann = fixture(GcPauseFixture.class, CompositeChaosGcPause.class);
      assertThat(new GcPauseComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new GcPauseComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Deadlock
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosDeadlock")
  class DeadlockTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosDeadlock.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("DeadlockComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosDeadlock.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(DeadlockComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosDeadlock ann = fixture(DeadlockFixture.class, CompositeChaosDeadlock.class);
      assertThat(new DeadlockComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new DeadlockComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ThreadStarvation
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosThreadStarvation")
  class ThreadStarvationTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosThreadStarvation.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ThreadStarvationComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosThreadStarvation.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(ThreadStarvationComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosThreadStarvation ann =
          fixture(ThreadStarvationFixture.class, CompositeChaosThreadStarvation.class);
      assertThat(new ThreadStarvationComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ThreadStarvationComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // VirtualThreadPinning
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosVirtualThreadPinning")
  class VirtualThreadPinningTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosVirtualThreadPinning.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("VirtualThreadPinningComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosVirtualThreadPinning.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(VirtualThreadPinningComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosVirtualThreadPinning ann =
          fixture(VirtualThreadPinningFixture.class, CompositeChaosVirtualThreadPinning.class);
      assertThat(new VirtualThreadPinningComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new VirtualThreadPinningComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SafepointStorm
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSafepointStorm")
  class SafepointStormTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSafepointStorm.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("SafepointStormComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosSafepointStorm.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(SafepointStormComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosSafepointStorm ann =
          fixture(SafepointStormFixture.class, CompositeChaosSafepointStorm.class);
      assertThat(new SafepointStormComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SafepointStormComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // MonitorContention
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosMonitorContention")
  class MonitorContentionTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosMonitorContention.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("MonitorContentionComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosMonitorContention.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(MonitorContentionComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosMonitorContention ann =
          fixture(MonitorContentionFixture.class, CompositeChaosMonitorContention.class);
      assertThat(new MonitorContentionComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new MonitorContentionComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // MetaspacePressure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosMetaspacePressure")
  class MetaspacePressureTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosMetaspacePressure.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("MetaspacePressureComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosMetaspacePressure.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(MetaspacePressureComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosMetaspacePressure ann =
          fixture(MetaspacePressureFixture.class, CompositeChaosMetaspacePressure.class);
      assertThat(new MetaspacePressureComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new MetaspacePressureComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DirectBufferLeak
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosDirectBufferLeak")
  class DirectBufferLeakTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosDirectBufferLeak.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("DirectBufferLeakComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosDirectBufferLeak.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(DirectBufferLeakComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosDirectBufferLeak ann =
          fixture(DirectBufferLeakFixture.class, CompositeChaosDirectBufferLeak.class);
      assertThat(new DirectBufferLeakComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new DirectBufferLeakComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CodeCachePressure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosCodeCachePressure")
  class CodeCachePressureTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosCodeCachePressure.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("CodeCachePressureComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosCodeCachePressure.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(CodeCachePressureComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosCodeCachePressure ann =
          fixture(CodeCachePressureFixture.class, CompositeChaosCodeCachePressure.class);
      assertThat(new CodeCachePressureComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new CodeCachePressureComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // FinalizerBacklog
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosFinalizerBacklog")
  class FinalizerBacklogTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosFinalizerBacklog.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("FinalizerBacklogComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosFinalizerBacklog.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(FinalizerBacklogComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosFinalizerBacklog ann =
          fixture(FinalizerBacklogFixture.class, CompositeChaosFinalizerBacklog.class);
      assertThat(new FinalizerBacklogComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new FinalizerBacklogComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // StringInternStorm
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosStringInternStorm")
  class StringInternStormTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosStringInternStorm.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("StringInternStormComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosStringInternStorm.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(StringInternStormComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosStringInternStorm ann =
          fixture(StringInternStormFixture.class, CompositeChaosStringInternStorm.class);
      assertThat(new StringInternStormComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new StringInternStormComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ThreadLocalLeak
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosThreadLocalLeak")
  class ThreadLocalLeakTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosThreadLocalLeak.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("ThreadLocalLeakComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosThreadLocalLeak.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(ThreadLocalLeakComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosThreadLocalLeak ann =
          fixture(ThreadLocalLeakFixture.class, CompositeChaosThreadLocalLeak.class);
      assertThat(new ThreadLocalLeakComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ThreadLocalLeakComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ReferenceQueueFlood
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosReferenceQueueFlood")
  class ReferenceQueueFloodTests {

    @Test
    @DisplayName("annotation carries MILD + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosReferenceQueueFlood.class);
      assertThat(meta.severity()).isEqualTo(Severity.MILD);
      assertThat(meta.composer()).endsWith("ReferenceQueueFloodComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosReferenceQueueFlood.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(ReferenceQueueFloodComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosReferenceQueueFlood ann =
          fixture(ReferenceQueueFloodFixture.class, CompositeChaosReferenceQueueFlood.class);
      assertThat(new ReferenceQueueFloodComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ReferenceQueueFloodComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SpuriousWakeup
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSpuriousWakeup")
  class SpuriousWakeupTests {

    @Test
    @DisplayName("annotation carries MILD + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSpuriousWakeup.class);
      assertThat(meta.severity()).isEqualTo(Severity.MILD);
      assertThat(meta.composer()).endsWith("SpuriousWakeupComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosSpuriousWakeup.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(SpuriousWakeupComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosSpuriousWakeup ann =
          fixture(SpuriousWakeupFixture.class, CompositeChaosSpuriousWakeup.class);
      assertThat(new SpuriousWakeupComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SpuriousWakeupComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ZipBomb
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosZipBomb")
  class ZipBombTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosZipBomb.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ZipBombComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosZipBomb.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(ZipBombComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosZipBomb ann = fixture(ZipBombFixture.class, CompositeChaosZipBomb.class);
      assertThat(new ZipBombComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ZipBombComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ConnectionPoolExhaustion
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosConnectionPoolExhaustion")
  class ConnectionPoolExhaustionTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosConnectionPoolExhaustion.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ConnectionPoolExhaustionComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosConnectionPoolExhaustion.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(ConnectionPoolExhaustionComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosConnectionPoolExhaustion ann =
          fixture(ConnectionPoolExhaustionFixture.class, CompositeChaosConnectionPoolExhaustion.class);
      assertThat(new ConnectionPoolExhaustionComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ConnectionPoolExhaustionComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SlowQuery
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSlowQuery")
  class SlowQueryTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSlowQuery.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("SlowQueryComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosSlowQuery.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(SlowQueryComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosSlowQuery ann =
          fixture(SlowQueryFixture.class, CompositeChaosSlowQuery.class);
      assertThat(new SlowQueryComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SlowQueryComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // JdbcRollbackFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosJdbcRollbackFailure")
  class JdbcRollbackFailureTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosJdbcRollbackFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("JdbcRollbackFailureComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosJdbcRollbackFailure.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(JdbcRollbackFailureComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosJdbcRollbackFailure ann =
          fixture(JdbcRollbackFailureFixture.class, CompositeChaosJdbcRollbackFailure.class);
      assertThat(new JdbcRollbackFailureComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new JdbcRollbackFailureComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HttpClientCascade
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosHttpClientCascade")
  class HttpClientCascadeTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosHttpClientCascade.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("HttpClientCascadeComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosHttpClientCascade.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(HttpClientCascadeComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosHttpClientCascade ann =
          fixture(HttpClientCascadeFixture.class, CompositeChaosHttpClientCascade.class);
      assertThat(new HttpClientCascadeComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new HttpClientCascadeComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HttpServerError5xx
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosHttpServerError5xx")
  class HttpServerError5xxTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosHttpServerError5xx.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("HttpServerError5xxComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosHttpServerError5xx.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(HttpServerError5xxComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosHttpServerError5xx ann =
          fixture(HttpServerError5xxFixture.class, CompositeChaosHttpServerError5xx.class);
      assertThat(new HttpServerError5xxComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new HttpServerError5xxComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ShutdownHookHang
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosShutdownHookHang")
  class ShutdownHookHangTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosShutdownHookHang.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ShutdownHookHangComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosShutdownHookHang.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(ShutdownHookHangComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosShutdownHookHang ann =
          fixture(ShutdownHookHangFixture.class, CompositeChaosShutdownHookHang.class);
      assertThat(new ShutdownHookHangComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ShutdownHookHangComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ScheduledTaskMissed
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosScheduledTaskMissed")
  class ScheduledTaskMissedTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosScheduledTaskMissed.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("ScheduledTaskMissedComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosScheduledTaskMissed.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(ScheduledTaskMissedComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosScheduledTaskMissed ann =
          fixture(ScheduledTaskMissedFixture.class, CompositeChaosScheduledTaskMissed.class);
      assertThat(new ScheduledTaskMissedComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ScheduledTaskMissedComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // BlockingQueueOverflow
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosBlockingQueueOverflow")
  class BlockingQueueOverflowTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosBlockingQueueOverflow.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("BlockingQueueOverflowComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosBlockingQueueOverflow.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(BlockingQueueOverflowComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosBlockingQueueOverflow ann =
          fixture(BlockingQueueOverflowFixture.class, CompositeChaosBlockingQueueOverflow.class);
      assertThat(new BlockingQueueOverflowComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new BlockingQueueOverflowComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompletableFutureCancellation
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosCompletableFutureCancellation")
  class CompletableFutureCancellationTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosCompletableFutureCancellation.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("CompletableFutureCancellationComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosCompletableFutureCancellation.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(CompletableFutureCancellationComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosCompletableFutureCancellation ann =
          fixture(CompletableFutureCancellationFixture.class, CompositeChaosCompletableFutureCancellation.class);
      assertThat(new CompletableFutureCancellationComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new CompletableFutureCancellationComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ClassLoadFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosClassLoadFailure")
  class ClassLoadFailureTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosClassLoadFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ClassLoadFailureComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosClassLoadFailure.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(ClassLoadFailureComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosClassLoadFailure ann =
          fixture(ClassLoadFailureFixture.class, CompositeChaosClassLoadFailure.class);
      assertThat(new ClassLoadFailureComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ClassLoadFailureComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // JndiInjection
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosJndiInjection")
  class JndiInjectionTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosJndiInjection.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("JndiInjectionComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosJndiInjection.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(JndiInjectionComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosJndiInjection ann =
          fixture(JndiInjectionFixture.class, CompositeChaosJndiInjection.class);
      assertThat(new JndiInjectionComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new JndiInjectionComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // UnsafeDeserialization
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosUnsafeDeserialization")
  class UnsafeDeserializationTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosUnsafeDeserialization.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("UnsafeDeserializationComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosUnsafeDeserialization.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(UnsafeDeserializationComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosUnsafeDeserialization ann =
          fixture(UnsafeDeserializationFixture.class, CompositeChaosUnsafeDeserialization.class);
      assertThat(new UnsafeDeserializationComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new UnsafeDeserializationComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // NativeLibraryLoadFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosNativeLibraryLoadFailure")
  class NativeLibraryLoadFailureTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosNativeLibraryLoadFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("NativeLibraryLoadFailureComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosNativeLibraryLoadFailure.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(NativeLibraryLoadFailureComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosNativeLibraryLoadFailure ann =
          fixture(NativeLibraryLoadFailureFixture.class, CompositeChaosNativeLibraryLoadFailure.class);
      assertThat(new NativeLibraryLoadFailureComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new NativeLibraryLoadFailureComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // JmxInvocationStorm
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosJmxInvocationStorm")
  class JmxInvocationStormTests {

    @Test
    @DisplayName("annotation carries MILD + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosJmxInvocationStorm.class);
      assertThat(meta.severity()).isEqualTo(Severity.MILD);
      assertThat(meta.composer()).endsWith("JmxInvocationStormComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosJmxInvocationStorm.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(JmxInvocationStormComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosJmxInvocationStorm ann =
          fixture(JmxInvocationStormFixture.class, CompositeChaosJmxInvocationStorm.class);
      assertThat(new JmxInvocationStormComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new JmxInvocationStormComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SslHandshakeFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSslHandshakeFailure")
  class SslHandshakeFailureTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSslHandshakeFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("SslHandshakeFailureComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosSslHandshakeFailure.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(SslHandshakeFailureComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosSslHandshakeFailure ann =
          fixture(SslHandshakeFailureFixture.class, CompositeChaosSslHandshakeFailure.class);
      assertThat(new SslHandshakeFailureComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new SslHandshakeFailureComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // MethodExceptionInjection
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosMethodExceptionInjection")
  class MethodExceptionInjectionTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosMethodExceptionInjection.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("MethodExceptionInjectionComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosMethodExceptionInjection.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(MethodExceptionInjectionComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosMethodExceptionInjection ann =
          fixture(MethodExceptionInjectionFixture.class, CompositeChaosMethodExceptionInjection.class);
      assertThat(new MethodExceptionInjectionComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new MethodExceptionInjectionComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // MethodReturnCorruption
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosMethodReturnCorruption")
  class MethodReturnCorruptionTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosMethodReturnCorruption.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("MethodReturnCorruptionComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosMethodReturnCorruption.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(MethodReturnCorruptionComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosMethodReturnCorruption ann =
          fixture(MethodReturnCorruptionFixture.class, CompositeChaosMethodReturnCorruption.class);
      assertThat(new MethodReturnCorruptionComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new MethodReturnCorruptionComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ClockSkewInProcess
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosClockSkewInProcess")
  class ClockSkewInProcessTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosClockSkewInProcess.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("ClockSkewInProcessComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosClockSkewInProcess.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(ClockSkewInProcessComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosClockSkewInProcess ann =
          fixture(ClockSkewInProcessFixture.class, CompositeChaosClockSkewInProcess.class);
      assertThat(new ClockSkewInProcessComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new ClockSkewInProcessComposer().removeAll(container(), List.of());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // FailingShutdownHook
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosFailingShutdownHook")
  class FailingShutdownHookTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosFailingShutdownHook.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("FailingShutdownHookComposer");
    }

    @Test
    @DisplayName("annotation is @Repeatable")
    void isRepeatable() {
      assertThat(CompositeChaosFailingShutdownHook.class.isAnnotationPresent(Repeatable.class)).isTrue();
    }

    @Test
    @DisplayName("composer implements L2Composer")
    void composerIsL2Composer() {
      assertThat(L2Composer.class).isAssignableFrom(FailingShutdownHookComposer.class);
    }

    @Test
    @DisplayName("describe() returns non-empty list")
    void describe() {
      final CompositeChaosFailingShutdownHook ann =
          fixture(FailingShutdownHookFixture.class, CompositeChaosFailingShutdownHook.class);
      assertThat(new FailingShutdownHookComposer().describe(ann)).isNotEmpty();
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      new FailingShutdownHookComposer().removeAll(container(), List.of());
    }
  }
}
