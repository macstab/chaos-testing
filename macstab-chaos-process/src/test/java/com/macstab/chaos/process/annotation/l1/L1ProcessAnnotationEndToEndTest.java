/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1;

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
import com.macstab.chaos.process.annotation.l1.fork.ChaosForkEnomem;
import com.macstab.chaos.process.annotation.l1.pthread_create.ChaosPthreadCreateEagain;
import com.macstab.chaos.process.annotation.l1.wildcard.ChaosWildcardEacces;

/**
 * End-to-end integration test for the process L1 annotation path.
 *
 * <p>Exercises the full chain: L1 annotation on a fixture class → {@link L1AnnotationProcessor}
 * reflective dispatch → {@code ProcessErrnoTranslator} → {@code LibchaosProcessChaos.apply()} →
 * config-file write inside a real running container. Verifies both class-scope application and
 * post-remove cleanup, on both glibc and musl base images.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("L1 process annotations — end-to-end annotation path")
class L1ProcessAnnotationEndToEndTest {

  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";
  private static final String CONF = LibchaosLib.PROCESS.getConfigPath();

  // ==================== Fixture classes ====================

  @ChaosForkEnomem
  static class WithForkEnomem {}

  @ChaosPthreadCreateEagain
  static class WithPthreadCreateEagain {}

  @ChaosWildcardEacces
  static class WithWildcardEacces {}

  // ==================== Tests ====================

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosForkEnomem writes fork:ERRNO:ENOMEM to config and removes cleanly")
  void forkEnomemWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithForkEnomem.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("fork").contains("ENOMEM");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("ENOMEM");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName(
      "@ChaosPthreadCreateEagain writes pthread_create:ERRNO:EAGAIN to config and removes cleanly")
  void pthreadCreateEagainWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithPthreadCreateEagain.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("pthread_create").contains("EAGAIN");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EAGAIN");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName(
      "@ChaosWildcardEacces writes wildcard (*):ERRNO:EACCES to config and removes cleanly")
  void wildcardEaccesWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithWildcardEacces.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      // Strip LD_PRELOAD so the WILDCARD rule doesn't block the execve of `cat` itself.
      final var conf =
          container.execInContainer("env", "-u", "LD_PRELOAD", "/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("EACCES");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "env",
              "-u",
              "LD_PRELOAD",
              "/bin/sh",
              "-c",
              "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EACCES");
    }
  }

  // ==================== Helpers ====================

  private static GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.PROCESS).prepare(c);
    c.start();
    return c;
  }

  private static List<ContainerHandle> containers(final GenericContainer<?> c) {
    return List.of(new ContainerHandle(c, "", Override.class));
  }
}
