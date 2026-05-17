/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;

/**
 * Integration tests for {@link LibchaosTimeChaos} against real containers.
 *
 * <p>Exercises the full lifecycle on both glibc ({@code debian:bookworm-slim}) and musl ({@code
 * alpine:3.20}) base images: pre-start preparation, post-start rule application (including the
 * per-clock {@code OFFSET}), config-file persistence, cleanup. Actual fault delivery is delegated
 * to libchaos-time's own end-to-end suite — this test verifies the Java-side wiring.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LibchaosTimeChaos — integration (debian + alpine)")
class LibchaosTimeChaosIntegrationTest {

  private static final String CONFIG_PATH = "/tmp/.chaos-time.conf";
  private static final String LIBRARY_PATH = "/usr/local/lib/libchaos-time.so";
  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";

  private GenericContainer<?> container;
  private LibchaosTimeChaos chaos;

  private GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.TIME).prepare(c);
    c.start();
    return c;
  }

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      container.stop();
    }
  }

  @Nested
  @DisplayName("prepare lifecycle")
  class PrepareLifecycle {
    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    void supportsAfterPrepare(final String image) {
      container = prepared(image);
      chaos = new LibchaosTimeChaos();
      assertThat(chaos.supports(container)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    void libraryDeployed(final String image) throws Exception {
      container = prepared(image);
      assertThat(container.execInContainer("test", "-f", LIBRARY_PATH).getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    void ldPreload(final String image) throws Exception {
      container = prepared(image);
      assertThat(container.execInContainer("/bin/sh", "-c", "echo \"$LD_PRELOAD\"").getStdout())
          .contains(LIBRARY_PATH);
    }
  }

  @Nested
  @DisplayName("apply / remove against a real container")
  class ApplyRemove {

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("apply() writes ERRNO rule (probability omitted at 1.0)")
    void applyWritesRule(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosTimeChaos();

      final RuleHandle handle =
          chaos.apply(container, TimeRule.errno(TimeSelector.CLOCK_GETTIME, TimeErrno.EINVAL));

      final var cat = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(cat.getStdout())
          .contains("clock_gettime:ERRNO:EINVAL")
          .contains("# " + handle.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("failNanosleep with low probability includes the @suffix")
    void failNanosleepProbability(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosTimeChaos();

      chaos.failNanosleep(container, 0.001);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("nanosleep:ERRNO:EINTR@0.001");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("skewClock emits clock_gettime/<clock>:OFFSET:<signedMs>")
    void skewClockNegative(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosTimeChaos();

      chaos.skewClock(container, TimeClock.MONOTONIC, Duration.ofMillis(-1500));

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("clock_gettime/monotonic:OFFSET:-1500");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("skewClock on realtime emits positive signed millis")
    void skewClockPositive(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosTimeChaos();

      chaos.skewClock(container, TimeClock.REALTIME, Duration.ofMillis(60_000));

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("clock_gettime/realtime:OFFSET:60000");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("slowClockGet emits clock_gettime:LATENCY:<ms>")
    void slowClockGet(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosTimeChaos();

      chaos.slowClockGet(container, Duration.ofMillis(50));

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("clock_gettime:LATENCY:50");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("failUsleep with EINTR emits usleep:ERRNO:EINTR")
    void failUsleep(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosTimeChaos();

      chaos.failUsleep(container, 0.5);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("usleep:ERRNO:EINTR@0.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    void removeOne(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosTimeChaos();

      final RuleHandle handle =
          chaos.apply(container, TimeRule.errno(TimeSelector.CLOCK_GETTIME, TimeErrno.EINVAL));

      chaos.remove(container, handle);

      final var cat =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true");
      assertThat(cat.getStdout()).doesNotContain("# " + handle.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    void removeAll(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosTimeChaos();

      chaos.failClockGet(container, 0.01);
      chaos.skewClock(container, TimeClock.MONOTONIC, Duration.ofMillis(-1500));

      chaos.removeAll(container);

      final var cat =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true");
      assertThat(cat.getStdout()).doesNotContain("clock_gettime:").doesNotContain("clock_gettime/");
    }
  }

  @Nested
  @DisplayName("pre-flight gate")
  class PreflightGate {
    @Test
    void unpreparedThrows() {
      container =
          new GenericContainer<>(DockerImageName.parse(DEBIAN)).withCommand("sleep", "infinity");
      container.start();
      chaos = new LibchaosTimeChaos();

      assertThatThrownBy(() -> chaos.failClockGet(container, 0.001))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("time")
          .hasMessageContaining("@SyscallLevelChaos");
    }
  }
}
