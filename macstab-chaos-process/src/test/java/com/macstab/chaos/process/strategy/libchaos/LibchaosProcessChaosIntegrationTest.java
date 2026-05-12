/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessRule;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Integration tests for {@link LibchaosProcessChaos} against real containers.
 *
 * <p>Exercises the full lifecycle on both glibc ({@code debian:bookworm-slim}) and musl ({@code
 * alpine:3.20}) base images: pre-start preparation, post-start rule application (including {@code
 * FAIL_AFTER}), config-file persistence, cleanup. Actual fault delivery is delegated to
 * libchaos-process's own end-to-end suite — this test verifies the Java-side wiring.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LibchaosProcessChaos — integration (debian + alpine)")
class LibchaosProcessChaosIntegrationTest {

  private static final String CONFIG_PATH = "/tmp/.chaos-process.conf";
  private static final String LIBRARY_PATH = "/usr/local/lib/libchaos-process.so";
  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";

  private GenericContainer<?> container;
  private LibchaosProcessChaos chaos;

  private GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.PROCESS).prepare(c);
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
      chaos = new LibchaosProcessChaos();
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
      chaos = new LibchaosProcessChaos();

      final RuleHandle handle =
          chaos.apply(
              container, ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN));

      final var cat = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(cat.getStdout())
          .contains("pthread_create:ERRNO:EAGAIN")
          .contains("# " + handle.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("failFork with low probability includes the @suffix")
    void failForkProbability(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosProcessChaos();

      chaos.failFork(container, 0.001);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("fork:ERRNO:EAGAIN@0.001");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("exhaustThreadPool emits FAIL_AFTER:EAGAIN,N")
    void exhaustThreadPool(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosProcessChaos();

      chaos.exhaustThreadPool(container, 128);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("pthread_create:FAIL_AFTER:EAGAIN,128");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("exhaustProcessLimit emits fork:FAIL_AFTER:EAGAIN,N")
    void exhaustProcessLimit(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosProcessChaos();

      chaos.exhaustProcessLimit(container, 64);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("fork:FAIL_AFTER:EAGAIN,64");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("signalInterruptWait emits waitpid:ERRNO:EINTR")
    void signalInterruptWait(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosProcessChaos();

      chaos.signalInterruptWait(container, 0.1);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("waitpid:ERRNO:EINTR@0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("failExecBadFormat emits execve:ERRNO:ENOEXEC")
    void failExecBadFormat(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosProcessChaos();

      chaos.failExecBadFormat(container, 0.5);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("execve:ERRNO:ENOEXEC@0.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    void removeOne(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosProcessChaos();

      final RuleHandle handle =
          chaos.apply(container, ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN));

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
      chaos = new LibchaosProcessChaos();

      chaos.failThreadCreation(container, 0.01);
      chaos.exhaustProcessLimit(container, 64);

      chaos.removeAll(container);

      final var cat =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true");
      assertThat(cat.getStdout()).doesNotContain("pthread_create:").doesNotContain("fork:");
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
      chaos = new LibchaosProcessChaos();

      assertThatThrownBy(() -> chaos.failThreadCreation(container, 0.001))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("process")
          .hasMessageContaining("@SyscallLevelChaos");
    }
  }
}
