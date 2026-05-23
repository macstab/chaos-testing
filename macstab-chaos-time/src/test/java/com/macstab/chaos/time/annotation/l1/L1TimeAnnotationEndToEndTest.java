/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1;

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
import com.macstab.chaos.time.annotation.l1.clock_gettime.ChaosClockGettimeEfault;
import com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepEintr;
import com.macstab.chaos.time.annotation.l1.wildcard.ChaosWildcardLatency;

/**
 * End-to-end integration test for the time L1 annotation path.
 *
 * <p>Exercises the full chain: L1 annotation on a fixture class → {@link L1AnnotationProcessor}
 * reflective dispatch → {@code TimeErrnoTranslator} / {@code TimeLatencyTranslator} →
 * {@code LibchaosTimeChaos.apply()} → config-file write inside a real running container.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("L1 time annotations — end-to-end annotation path")
class L1TimeAnnotationEndToEndTest {

  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";
  private static final String CONF = LibchaosLib.TIME.getConfigPath();

  // ==================== Fixture classes ====================

  @ChaosClockGettimeEfault
  static class WithClockGettimeEfault {}

  @ChaosNanosleepEintr
  static class WithNanosleepEintr {}

  @ChaosWildcardLatency
  static class WithWildcardLatency {}

  // ==================== Tests ====================

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosClockGettimeEfault writes clock_gettime:ERRNO:EFAULT to config and removes cleanly")
  void clockGettimeEfaultWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithClockGettimeEfault.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("clock_gettime").contains("EFAULT");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EFAULT");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosNanosleepEintr writes nanosleep:ERRNO:EINTR to config and removes cleanly")
  void nanosleepEintrWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithNanosleepEintr.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("nanosleep").contains("EINTR");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EINTR");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosTimeWildcardLatency writes wildcard LATENCY rule to config and removes cleanly")
  void wildcardLatencyWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithWildcardLatency.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("LATENCY");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("LATENCY");
    }
  }

  // ==================== Helpers ====================

  private static GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.TIME).prepare(c);
    c.start();
    return c;
  }

  private static List<ContainerHandle> containers(final GenericContainer<?> c) {
    return List.of(new ContainerHandle(c, "", Override.class));
  }
}
