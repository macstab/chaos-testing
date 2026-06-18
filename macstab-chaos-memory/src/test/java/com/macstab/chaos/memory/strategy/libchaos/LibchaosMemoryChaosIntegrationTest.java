/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.strategy.libchaos;

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
import com.macstab.chaos.memory.api.RuleHandle;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

/**
 * Integration tests for {@link LibchaosMemoryChaos} against real containers.
 *
 * <p>Exercises the full lifecycle on both glibc ({@code debian:bookworm-slim}) and musl ({@code
 * alpine:3.20}) base images: pre-start preparation, post-start rule application, config- file
 * persistence, cleanup. Actual fault delivery (mmap returning MAP_FAILED etc.) is delegated to the
 * libchaos-memory library's own end-to-end suite — this test verifies the Java-side wiring.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LibchaosMemoryChaos — integration (debian + alpine)")
class LibchaosMemoryChaosIntegrationTest {

  private static final String CONFIG_PATH = "/tmp/.chaos-memory.conf";
  private static final String LIBRARY_PATH = "/usr/local/lib/libchaos-memory.so";
  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";

  private GenericContainer<?> container;
  private LibchaosMemoryChaos chaos;

  private GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.MEMORY).prepare(c);
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
      chaos = new LibchaosMemoryChaos();
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
    @DisplayName("apply() writes ENOMEM rule into the config file (probability omitted at 1.0)")
    void applyWritesRule(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosMemoryChaos();

      final RuleHandle handle =
          chaos.apply(container, MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM));

      final var cat = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(cat.getStdout())
          .contains("mmap/anon:ERRNO:ENOMEM")
          .contains("# " + handle.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("failHeapAllocation with low probability includes the @suffix")
    void failHeapAllocation(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosMemoryChaos();

      chaos.failHeapAllocation(container, 0.001);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("mmap/anon:ERRNO:ENOMEM@0.001");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("failJitCompilation emits mprotect:ERRNO:EACCES")
    void failJit(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosMemoryChaos();

      chaos.failJitCompilation(container, 0.5);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("mprotect:ERRNO:EACCES@0.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("slowMadvise emits madvise:LATENCY:millis")
    void slowMadvise(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosMemoryChaos();

      chaos.slowMadvise(container, Duration.ofMillis(75));

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("madvise:LATENCY:75");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    void removeOne(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosMemoryChaos();

      final RuleHandle handle =
          chaos.apply(container, MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM));

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
      chaos = new LibchaosMemoryChaos();

      chaos.failHeapAllocation(container, 0.001);
      chaos.failJitCompilation(container, 0.5);

      chaos.removeAll(container);

      final var cat =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true");
      assertThat(cat.getStdout()).doesNotContain("mmap/anon:").doesNotContain("mprotect:");
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
      chaos = new LibchaosMemoryChaos();

      assertThatThrownBy(() -> chaos.failHeapAllocation(container, 0.001))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("memory")
          .hasMessageContaining("@SyscallLevelChaos");
    }
  }
}
