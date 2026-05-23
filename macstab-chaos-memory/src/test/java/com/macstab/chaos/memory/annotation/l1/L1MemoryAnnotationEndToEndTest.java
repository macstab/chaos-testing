/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.extension.ChaosApplicationReport;
import com.macstab.chaos.core.extension.L1AnnotationProcessor;
import com.macstab.chaos.core.extension.L1AnnotationProcessor.AppliedL1;
import com.macstab.chaos.core.extension.L1AnnotationProcessor.ContainerHandle;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.memory.annotation.l1.mmap_anon.ChaosMmapAnonEnomem;
import com.macstab.chaos.memory.annotation.l1.mmap_anon.ChaosMmapAnonEperm;
import com.macstab.chaos.memory.annotation.l1.mprotect.ChaosMprotectEacces;

/**
 * End-to-end integration test for the memory L1 annotation path.
 *
 * <p>Exercises the full chain: L1 annotation on a fixture class → {@link L1AnnotationProcessor}
 * reflective dispatch → {@code MemoryErrnoTranslator} → {@code LibchaosMemoryChaos.apply()} →
 * config-file write inside a real running container. Verifies both class-scope application and
 * post-remove cleanup, on both glibc and musl base images.
 *
 * <p>This complements {@code LibchaosMemoryChaosIntegrationTest} (which tests the strategy layer
 * imperatively) by exercising the annotation-driven path that {@code ChaosTestingExtension} uses
 * at runtime.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("L1 memory annotations — end-to-end annotation path")
class L1MemoryAnnotationEndToEndTest {

  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";
  private static final String CONF = LibchaosLib.MEMORY.getConfigPath();

  // ==================== Fixture classes carrying L1 annotations ====================

  @ChaosMmapAnonEnomem
  static class WithMmapAnonEnomem {}

  @ChaosMmapAnonEperm
  static class WithMmapAnonEperm {}

  @ChaosMprotectEacces
  static class WithMprotectEacces {}

  // ==================== Tests ====================

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosMmapAnonEnomem writes mmap/anon:ERRNO:ENOMEM to config and removes cleanly")
  void mmapAnonEnomemWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithMmapAnonEnomem.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("mmap/anon").contains("ENOMEM");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("ENOMEM");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosMmapAnonEperm writes mmap/anon:ERRNO:EPERM to config and removes cleanly")
  void mmapAnonEpermWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithMmapAnonEperm.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("mmap/anon").contains("EPERM");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EPERM");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosMprotectEacces writes mprotect:ERRNO:EACCES to config and removes cleanly")
  void mprotectEaccesWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithMprotectEacces.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("mprotect").contains("EACCES");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EACCES");
    }
  }

  // ==================== Helpers ====================

  private static GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.MEMORY).prepare(c);
    c.start();
    return c;
  }

  private static List<ContainerHandle> containers(final GenericContainer<?> c) {
    return List.of(new ContainerHandle(c, "", Override.class));
  }
}
