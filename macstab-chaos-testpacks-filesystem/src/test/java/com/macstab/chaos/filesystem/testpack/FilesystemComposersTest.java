/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.testpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.api.AdvancedFilesystemChaos;
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.Effect;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.testpack.composers.DiskFullComposer;
import com.macstab.chaos.filesystem.testpack.composers.EioOnReadComposer;
import com.macstab.chaos.filesystem.testpack.composers.FdExhaustionComposer;
import com.macstab.chaos.filesystem.testpack.composers.MetadataFailureComposer;
import com.macstab.chaos.filesystem.testpack.composers.ReadCorruptionComposer;
import com.macstab.chaos.filesystem.testpack.composers.ReadOnlyFilesystemComposer;
import com.macstab.chaos.filesystem.testpack.composers.RenameRaceComposer;
import com.macstab.chaos.filesystem.testpack.composers.SlowDiskComposer;
import com.macstab.chaos.filesystem.testpack.composers.WalFsyncDelayComposer;
import com.macstab.chaos.filesystem.testpack.composers.WriteCorruptionComposer;

@DisplayName("Filesystem L2 composers — contract and rule-building")
class FilesystemComposersTest {

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

  // ── IoRule argument matchers ──────────────────────────────────────────────

  private static IoRule argErrnoRule(final IoOperation op, final Errno errno) {
    return org.mockito.ArgumentMatchers.argThat(
        rule ->
            rule.operation() == op
                && rule.effect() instanceof Effect.ErrnoFault ef
                && ef.errno() == errno);
  }

  private static IoRule argLatencyRule(final IoOperation op, final Duration delay) {
    return org.mockito.ArgumentMatchers.argThat(
        rule ->
            rule.operation() == op
                && rule.effect() instanceof Effect.Latency lat
                && lat.delay().equals(delay));
  }

  private static IoRule argCorruptRule(final IoOperation op) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> rule.operation() == op && rule.effect() instanceof Effect.Corrupt);
  }

  private static IoRule argTornRule(final IoOperation op) {
    return org.mockito.ArgumentMatchers.argThat(
        rule -> rule.operation() == op && rule.effect() instanceof Effect.Torn);
  }

  @SuppressWarnings("unchecked")
  private static <A extends java.lang.annotation.Annotation> A fixture(
      final Class<?> holder, final Class<A> type) {
    final A ann = holder.getAnnotation(type);
    assertThat(ann).as(type.getSimpleName() + " missing on " + holder.getSimpleName()).isNotNull();
    return ann;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosDiskFull
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosDiskFull")
  class DiskFullTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct composer FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosDiskFull.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("DiskFullComposer");
    }

    @Test
    @DisplayName("default path is wildcard, default toxicity is 1.0")
    void defaults() {
      final CompositeChaosDiskFull ann =
          fixture(DiskFullFixture.class, CompositeChaosDiskFull.class);
      assertThat(ann.path()).isEqualTo("*");
      assertThat(ann.toxicity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("describe() mentions ENOSPC")
    void describe() {
      final CompositeChaosDiskFull ann =
          fixture(DiskFullFixture.class, CompositeChaosDiskFull.class);
      final List<String> lines = new DiskFullComposer().describe(ann);
      assertThat(lines).isNotEmpty();
      assertThat(String.join(" ", lines)).containsIgnoringCase("ENOSPC");
    }

    @Test
    @DisplayName("apply() builds WRITE ENOSPC rule on wildcard path")
    void apply() {
      final CompositeChaosDiskFull ann =
          fixture(DiskFullFixture.class, CompositeChaosDiskFull.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new DiskFullComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argErrnoRule(IoOperation.WRITE, Errno.ENOSPC));
      }
    }

    @Test
    @DisplayName("removeAll() with empty list does nothing")
    void removeAllEmpty() {
      assertThatCode(() -> new DiskFullComposer().removeAll(container(), List.of()))
          .doesNotThrowAnyException();
    }

    @CompositeChaosDiskFull
    static class DiskFullFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosReadCorruption
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosReadCorruption")
  class ReadCorruptionTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct composer FQN + default toxicity 0.01")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosReadCorruption.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("ReadCorruptionComposer");
      final CompositeChaosReadCorruption ann =
          fixture(ReadCorruptionFixture.class, CompositeChaosReadCorruption.class);
      assertThat(ann.toxicity()).isEqualTo(0.01);
    }

    @Test
    @DisplayName("describe() mentions corruption")
    void describe() {
      final CompositeChaosReadCorruption ann =
          fixture(ReadCorruptionFixture.class, CompositeChaosReadCorruption.class);
      final List<String> lines = new ReadCorruptionComposer().describe(ann);
      assertThat(String.join(" ", lines)).containsIgnoringCase("corruption");
    }

    @Test
    @DisplayName("apply() builds READ CORRUPT rule")
    void apply() {
      final CompositeChaosReadCorruption ann =
          fixture(ReadCorruptionFixture.class, CompositeChaosReadCorruption.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new ReadCorruptionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argCorruptRule(IoOperation.READ));
      }
    }

    @CompositeChaosReadCorruption
    static class ReadCorruptionFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosWalFsyncDelay
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosWalFsyncDelay")
  class WalFsyncDelayTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default 500ms")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosWalFsyncDelay.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("WalFsyncDelayComposer");
      final CompositeChaosWalFsyncDelay ann =
          fixture(WalFsyncFixture.class, CompositeChaosWalFsyncDelay.class);
      assertThat(ann.latencyMs()).isEqualTo(500L);
    }

    @Test
    @DisplayName("describe() mentions latency value")
    void describe() {
      final CompositeChaosWalFsyncDelay ann =
          fixture(WalFsyncFixture.class, CompositeChaosWalFsyncDelay.class);
      final List<String> lines = new WalFsyncDelayComposer().describe(ann);
      assertThat(String.join(" ", lines)).contains("500");
    }

    @Test
    @DisplayName("apply() builds FSYNC and FDATASYNC LATENCY rules")
    void apply() {
      final CompositeChaosWalFsyncDelay ann =
          fixture(WalFsyncFixture.class, CompositeChaosWalFsyncDelay.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new WalFsyncDelayComposer().apply(c, ann);
        assertThat(handles).hasSize(2);
        verify(adv).apply(eq(c), argLatencyRule(IoOperation.FSYNC, Duration.ofMillis(500)));
        verify(adv).apply(eq(c), argLatencyRule(IoOperation.FDATASYNC, Duration.ofMillis(500)));
      }
    }

    @CompositeChaosWalFsyncDelay
    static class WalFsyncFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosReadOnlyFilesystem
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosReadOnlyFilesystem")
  class ReadOnlyFilesystemTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosReadOnlyFilesystem.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("ReadOnlyFilesystemComposer");
    }

    @Test
    @DisplayName("describe() mentions EACCES and read-only")
    void describe() {
      final CompositeChaosReadOnlyFilesystem ann =
          fixture(ReadOnlyFixture.class, CompositeChaosReadOnlyFilesystem.class);
      final List<String> lines = new ReadOnlyFilesystemComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("EACCES")
          .containsIgnoringCase("read-only");
    }

    @Test
    @DisplayName("apply() builds WRITE, RENAME_FROM and UNLINK EACCES rules (3 handles)")
    void apply() {
      final CompositeChaosReadOnlyFilesystem ann =
          fixture(ReadOnlyFixture.class, CompositeChaosReadOnlyFilesystem.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new ReadOnlyFilesystemComposer().apply(c, ann);
        assertThat(handles).hasSize(3);
        verify(adv).apply(eq(c), argErrnoRule(IoOperation.WRITE, Errno.EACCES));
        verify(adv).apply(eq(c), argErrnoRule(IoOperation.RENAME_FROM, Errno.EACCES));
        verify(adv).apply(eq(c), argErrnoRule(IoOperation.UNLINK, Errno.EACCES));
      }
    }

    @CompositeChaosReadOnlyFilesystem
    static class ReadOnlyFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosSlowDisk
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosSlowDisk")
  class SlowDiskTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default 200ms")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosSlowDisk.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("SlowDiskComposer");
      final CompositeChaosSlowDisk ann =
          fixture(SlowDiskFixture.class, CompositeChaosSlowDisk.class);
      assertThat(ann.latencyMs()).isEqualTo(200L);
    }

    @Test
    @DisplayName("describe() mentions latency value")
    void describe() {
      final CompositeChaosSlowDisk ann =
          fixture(SlowDiskFixture.class, CompositeChaosSlowDisk.class);
      final List<String> lines = new SlowDiskComposer().describe(ann);
      assertThat(String.join(" ", lines)).contains("200");
    }

    @Test
    @DisplayName("apply() builds READ and WRITE LATENCY rules (2 handles)")
    void apply() {
      final CompositeChaosSlowDisk ann =
          fixture(SlowDiskFixture.class, CompositeChaosSlowDisk.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new SlowDiskComposer().apply(c, ann);
        assertThat(handles).hasSize(2);
        verify(adv).apply(eq(c), argLatencyRule(IoOperation.READ, Duration.ofMillis(200)));
        verify(adv).apply(eq(c), argLatencyRule(IoOperation.WRITE, Duration.ofMillis(200)));
      }
    }

    @CompositeChaosSlowDisk
    static class SlowDiskFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosEioOnRead
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosEioOnRead")
  class EioOnReadTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN + default toxicity 0.5")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosEioOnRead.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("EioOnReadComposer");
      final CompositeChaosEioOnRead ann =
          fixture(EioOnReadFixture.class, CompositeChaosEioOnRead.class);
      assertThat(ann.toxicity()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("describe() mentions EIO")
    void describe() {
      final CompositeChaosEioOnRead ann =
          fixture(EioOnReadFixture.class, CompositeChaosEioOnRead.class);
      final List<String> lines = new EioOnReadComposer().describe(ann);
      assertThat(String.join(" ", lines)).containsIgnoringCase("EIO");
    }

    @Test
    @DisplayName("apply() builds READ EIO rule")
    void apply() {
      final CompositeChaosEioOnRead ann =
          fixture(EioOnReadFixture.class, CompositeChaosEioOnRead.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new EioOnReadComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argErrnoRule(IoOperation.READ, Errno.EIO));
      }
    }

    @CompositeChaosEioOnRead
    static class EioOnReadFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosRenameRace
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosRenameRace")
  class RenameRaceTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default toxicity 0.5")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosRenameRace.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("RenameRaceComposer");
      final CompositeChaosRenameRace ann =
          fixture(RenameRaceFixture.class, CompositeChaosRenameRace.class);
      assertThat(ann.toxicity()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("describe() mentions ENOENT and rename")
    void describe() {
      final CompositeChaosRenameRace ann =
          fixture(RenameRaceFixture.class, CompositeChaosRenameRace.class);
      final List<String> lines = new RenameRaceComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("ENOENT")
          .containsIgnoringCase("rename");
    }

    @Test
    @DisplayName("apply() builds RENAME_FROM ENOENT rule")
    void apply() {
      final CompositeChaosRenameRace ann =
          fixture(RenameRaceFixture.class, CompositeChaosRenameRace.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new RenameRaceComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argErrnoRule(IoOperation.RENAME_FROM, Errno.ENOENT));
      }
    }

    @CompositeChaosRenameRace
    static class RenameRaceFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosFdExhaustion
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosFdExhaustion")
  class FdExhaustionTests {

    @Test
    @DisplayName("annotation carries SEVERE + correct FQN + default toxicity 1.0")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosFdExhaustion.class);
      assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
      assertThat(meta.composer()).endsWith("FdExhaustionComposer");
      final CompositeChaosFdExhaustion ann =
          fixture(FdExhaustionFixture.class, CompositeChaosFdExhaustion.class);
      assertThat(ann.toxicity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("describe() mentions EMFILE")
    void describe() {
      final CompositeChaosFdExhaustion ann =
          fixture(FdExhaustionFixture.class, CompositeChaosFdExhaustion.class);
      final List<String> lines = new FdExhaustionComposer().describe(ann);
      assertThat(String.join(" ", lines)).containsIgnoringCase("EMFILE");
    }

    @Test
    @DisplayName("apply() builds OPEN EMFILE rule on wildcard path")
    void apply() {
      final CompositeChaosFdExhaustion ann =
          fixture(FdExhaustionFixture.class, CompositeChaosFdExhaustion.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new FdExhaustionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argErrnoRule(IoOperation.OPEN, Errno.EMFILE));
      }
    }

    @CompositeChaosFdExhaustion
    static class FdExhaustionFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosWriteCorruption
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosWriteCorruption")
  class WriteCorruptionTests {

    @Test
    @DisplayName("annotation carries CRITICAL + correct FQN + default toxicity 0.001")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosWriteCorruption.class);
      assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(meta.composer()).endsWith("WriteCorruptionComposer");
      final CompositeChaosWriteCorruption ann =
          fixture(WriteCorruptionFixture.class, CompositeChaosWriteCorruption.class);
      assertThat(ann.toxicity()).isEqualTo(0.001);
    }

    @Test
    @DisplayName("describe() mentions torn write / corruption")
    void describe() {
      final CompositeChaosWriteCorruption ann =
          fixture(WriteCorruptionFixture.class, CompositeChaosWriteCorruption.class);
      final List<String> lines = new WriteCorruptionComposer().describe(ann);
      assertThat(String.join(" ", lines)).containsIgnoringCase("torn");
    }

    @Test
    @DisplayName("apply() builds WRITE TORN rule")
    void apply() {
      final CompositeChaosWriteCorruption ann =
          fixture(WriteCorruptionFixture.class, CompositeChaosWriteCorruption.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new WriteCorruptionComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argTornRule(IoOperation.WRITE));
      }
    }

    @CompositeChaosWriteCorruption
    static class WriteCorruptionFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CompositeChaosMetadataFailure
  // ═══════════════════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("@CompositeChaosMetadataFailure")
  class MetadataFailureTests {

    @Test
    @DisplayName("annotation carries MODERATE + correct FQN + default toxicity 0.3")
    void annotationContract() {
      final ChaosL2 meta = chaosL2(CompositeChaosMetadataFailure.class);
      assertThat(meta.severity()).isEqualTo(Severity.MODERATE);
      assertThat(meta.composer()).endsWith("MetadataFailureComposer");
      final CompositeChaosMetadataFailure ann =
          fixture(MetadataFailureFixture.class, CompositeChaosMetadataFailure.class);
      assertThat(ann.toxicity()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("describe() mentions EIO and metadata")
    void describe() {
      final CompositeChaosMetadataFailure ann =
          fixture(MetadataFailureFixture.class, CompositeChaosMetadataFailure.class);
      final List<String> lines = new MetadataFailureComposer().describe(ann);
      assertThat(String.join(" ", lines))
          .containsIgnoringCase("EIO")
          .containsIgnoringCase("metadata");
    }

    @Test
    @DisplayName("apply() builds OPEN EIO rule")
    void apply() {
      final CompositeChaosMetadataFailure ann =
          fixture(MetadataFailureFixture.class, CompositeChaosMetadataFailure.class);
      final GenericContainer<?> c = container();
      final RuleHandle h = handle();
      final AdvancedFilesystemChaos adv = mock(AdvancedFilesystemChaos.class);
      final CompositeFilesystemChaos composite = mock(CompositeFilesystemChaos.class);
      when(composite.advanced()).thenReturn(adv);
      when(adv.apply(eq(c), any(IoRule.class))).thenReturn(h);

      try (final MockedStatic<CompositeFilesystemChaos> mocked =
          mockStatic(CompositeFilesystemChaos.class)) {
        mocked.when(CompositeFilesystemChaos::standard).thenReturn(composite);
        final List<Object> handles = new MetadataFailureComposer().apply(c, ann);
        assertThat(handles).containsExactly(h);
        verify(adv).apply(eq(c), argErrnoRule(IoOperation.OPEN, Errno.EIO));
      }
    }

    @CompositeChaosMetadataFailure
    static class MetadataFailureFixture {}
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // L2Composer contract — all composers implement the interface
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("all composers implement L2Composer")
  void allComposersImplementInterface() {
    assertThat(new DiskFullComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ReadCorruptionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new WalFsyncDelayComposer()).isInstanceOf(L2Composer.class);
    assertThat(new ReadOnlyFilesystemComposer()).isInstanceOf(L2Composer.class);
    assertThat(new SlowDiskComposer()).isInstanceOf(L2Composer.class);
    assertThat(new EioOnReadComposer()).isInstanceOf(L2Composer.class);
    assertThat(new RenameRaceComposer()).isInstanceOf(L2Composer.class);
    assertThat(new FdExhaustionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new WriteCorruptionComposer()).isInstanceOf(L2Composer.class);
    assertThat(new MetadataFailureComposer()).isInstanceOf(L2Composer.class);
  }

  @Test
  @DisplayName("all annotations are repeatable (carry @List container)")
  void allAnnotationsRepeatable() {
    assertThat(CompositeChaosDiskFull.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosReadCorruption.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosWalFsyncDelay.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosReadOnlyFilesystem.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosSlowDisk.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosEioOnRead.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(CompositeChaosRenameRace.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosFdExhaustion.class.getAnnotation(java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosWriteCorruption.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
    assertThat(
            CompositeChaosMetadataFailure.class.getAnnotation(
                java.lang.annotation.Repeatable.class))
        .isNotNull();
  }
}
