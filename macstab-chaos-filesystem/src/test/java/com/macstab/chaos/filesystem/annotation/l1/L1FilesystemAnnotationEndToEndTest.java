/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.macstab.chaos.filesystem.annotation.l1.read.ChaosReadEio;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteErofs;
import com.macstab.chaos.filesystem.annotation.l1.unlink.ChaosUnlinkEacces;

/**
 * End-to-end integration test for the filesystem L1 annotation path.
 *
 * <p>Exercises the full chain: L1 annotation on a fixture class → {@link L1AnnotationProcessor}
 * reflective dispatch → {@code IoErrnoTranslator} → {@code LibchaosIoFilesystemChaos.apply()} →
 * config-file write inside a real running container. Verifies both class-scope application and
 * post-remove cleanup, on both glibc and musl base images.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("L1 filesystem annotations — end-to-end annotation path")
class L1FilesystemAnnotationEndToEndTest {

  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";
  private static final String CONF = LibchaosLib.IO.getConfigPath();

  // ==================== Fixture classes ====================

  @ChaosReadEio
  static class WithReadEio {}

  @ChaosWriteErofs
  static class WithWriteErofs {}

  @ChaosUnlinkEacces
  static class WithUnlinkEacces {}

  // ==================== Tests ====================

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosReadEio writes read:ERRNO:EIO to config and removes cleanly")
  void readEioWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithReadEio.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("read").contains("EIO");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EIO");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosWriteErofs writes write:ERRNO:EROFS to config and removes cleanly")
  void writeErofsWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithWriteErofs.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("write").contains("EROFS");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EROFS");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosUnlinkEacces writes unlink:ERRNO:EACCES to config and removes cleanly")
  void unlinkEaccesWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithUnlinkEacces.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("unlink").contains("EACCES");

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
    new LibchaosTransport(LibchaosLib.IO).prepare(c);
    c.start();
    return c;
  }

  private static List<ContainerHandle> containers(final GenericContainer<?> c) {
    return List.of(new ContainerHandle(c, "", Override.class));
  }
}
