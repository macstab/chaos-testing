/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.connection.annotation.l1.connect.ChaosConnectEconnrefused;
import com.macstab.chaos.connection.annotation.l1.recv.ChaosRecvEconnreset;
import com.macstab.chaos.connection.annotation.l1.send.ChaosSendEpipe;
import com.macstab.chaos.core.extension.ChaosApplicationReport;
import com.macstab.chaos.core.extension.L1AnnotationProcessor;
import com.macstab.chaos.core.extension.L1AnnotationProcessor.AppliedL1;
import com.macstab.chaos.core.extension.L1AnnotationProcessor.ContainerHandle;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/**
 * End-to-end integration test for the connection L1 annotation path.
 *
 * <p>Exercises the full chain: L1 annotation on a fixture class → {@link L1AnnotationProcessor}
 * reflective dispatch → {@code ConnectionErrnoTranslator} → {@code LibchaosNetConnectionChaos.apply()}
 * → config-file write inside a real running container. Verifies both class-scope application and
 * post-remove cleanup, on both glibc and musl base images.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("L1 connection annotations — end-to-end annotation path")
class L1ConnectionAnnotationEndToEndTest {

  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";
  private static final String CONF = LibchaosLib.NET.getConfigPath();

  // ==================== Fixture classes ====================

  @ChaosConnectEconnrefused
  static class WithConnectEconnrefused {}

  @ChaosRecvEconnreset
  static class WithRecvEconnreset {}

  @ChaosSendEpipe
  static class WithSendEpipe {}

  // ==================== Tests ====================

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosConnectEconnrefused writes connect:ERRNO:ECONNREFUSED to config and removes cleanly")
  void connectEconnrefusedWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithConnectEconnrefused.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("connect").contains("ECONNREFUSED");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("ECONNREFUSED");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosRecvEconnreset writes recv:ERRNO:ECONNRESET to config and removes cleanly")
  void recvEconnresetWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithRecvEconnreset.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("recv").contains("ECONNRESET");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("ECONNRESET");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosSendEpipe writes send:ERRNO:EPIPE to config and removes cleanly")
  void sendEpipeWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithSendEpipe.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("send").contains("EPIPE");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EPIPE");
    }
  }

  // ==================== Helpers ====================

  private static GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.NET).prepare(c);
    c.start();
    return c;
  }

  private static List<ContainerHandle> containers(final GenericContainer<?> c) {
    return List.of(new ContainerHandle(c, "", Override.class));
  }
}
