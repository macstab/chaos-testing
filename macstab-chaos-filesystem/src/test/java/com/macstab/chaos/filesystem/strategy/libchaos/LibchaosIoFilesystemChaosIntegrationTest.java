/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.strategy.libchaos;

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
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

/**
 * Integration tests for {@link LibchaosIoFilesystemChaos} against real containers.
 *
 * <p>Exercises the full lifecycle on both glibc ({@code debian:bookworm-slim}) and musl ({@code
 * alpine:3.20}) base images: pre-start preparation, post-start rule application, config-file
 * persistence, and cleanup. Actual fault delivery (rules causing the right syscall errors) is
 * delegated to the libchaos-io library's own end-to-end suite — this test verifies the Java-side
 * wiring.
 *
 * <p>Each test method runs against both distributions via {@link ParameterizedTest} so glibc/musl
 * binary resolution is covered without a separate test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LibchaosIoFilesystemChaos — integration (debian + alpine)")
class LibchaosIoFilesystemChaosIntegrationTest {

  private static final String CONFIG_PATH = "/tmp/.chaos-io.conf";
  private static final String LIBRARY_PATH = "/usr/local/lib/libchaos-io.so";
  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";

  private GenericContainer<?> container;
  private LibchaosIoFilesystemChaos chaos;

  /** Builds and starts a prepared container for the given image. */
  private GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.IO).prepare(c);
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
    @DisplayName("supports() returns true on a prepared container")
    void supportsAfterPrepare(final String image) {
      container = prepared(image);
      chaos = new LibchaosIoFilesystemChaos();
      assertThat(chaos.supports(container)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName(".so is deployed at the canonical path")
    void libraryDeployed(final String image) throws Exception {
      container = prepared(image);
      final var result = container.execInContainer("test", "-f", LIBRARY_PATH);
      assertThat(result.getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("LD_PRELOAD env var contains the libchaos-io path")
    void ldPreloadSet(final String image) throws Exception {
      container = prepared(image);
      final var result = container.execInContainer("/bin/sh", "-c", "echo \"$LD_PRELOAD\"");
      assertThat(result.getStdout()).contains(LIBRARY_PATH);
    }
  }

  @Nested
  @DisplayName("apply / remove against a real container")
  class ApplyRemove {

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("apply() writes the wire-format rule into the libchaos-io config file")
    void applyWritesRule(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosIoFilesystemChaos();

      final RuleHandle handle =
          chaos.apply(
              container, IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 0.3));

      final var cat = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(cat.getStdout()).contains("/data:write:EIO:0.3").contains("# " + handle.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("tornWrite convenience verb produces the expected wire form")
    void tornWriteWireForm(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosIoFilesystemChaos();

      chaos.tornWrite(container, PathPrefix.path("/srv/wal"), 0.1);

      final var cat = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(cat.getStdout()).contains("/srv/wal:write:TORN:0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("slowFsync convenience verb produces fsync:LATENCY:millis")
    void slowFsyncWireForm(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosIoFilesystemChaos();

      chaos.slowFsync(container, PathPrefix.path("/srv/wal"), Duration.ofMillis(250));

      final var cat = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(cat.getStdout()).contains("/srv/wal:fsync:LATENCY:250");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("remove(handle) deletes the matching rule from the config file")
    void removeDeletesRule(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosIoFilesystemChaos();

      final RuleHandle handle =
          chaos.apply(
              container, IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 1.0));

      chaos.remove(container, handle);

      final var cat =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true");
      assertThat(cat.getStdout()).doesNotContain("# " + handle.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("removeAll() clears every rule applied by this strategy")
    void removeAllWipesEverything(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosIoFilesystemChaos();

      chaos.apply(
          container, IoRule.errno(PathPrefix.path("/a"), IoOperation.WRITE, Errno.EIO, 1.0));
      chaos.apply(
          container, IoRule.errno(PathPrefix.path("/b"), IoOperation.WRITE, Errno.EIO, 1.0));

      chaos.removeAll(container);

      final var cat =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true");
      assertThat(cat.getStdout()).doesNotContain("/a:write").doesNotContain("/b:write");
    }
  }

  @Nested
  @DisplayName("pre-flight gate")
  class PreflightGate {

    @Test
    @DisplayName(
        "advanced verb on an unprepared container raises LibchaosNotPreparedException with @SyscallLevelChaos hint")
    void unpreparedThrows() {
      container =
          new GenericContainer<>(DockerImageName.parse(DEBIAN)).withCommand("sleep", "infinity");
      container.start();
      chaos = new LibchaosIoFilesystemChaos();

      final IoRule rule = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 1.0);

      assertThatThrownBy(() -> chaos.apply(container, rule))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("io")
          .hasMessageContaining("@SyscallLevelChaos");
    }
  }
}
