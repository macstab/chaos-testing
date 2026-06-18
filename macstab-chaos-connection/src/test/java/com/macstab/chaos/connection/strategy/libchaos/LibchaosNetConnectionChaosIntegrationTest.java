/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.strategy.libchaos;

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

import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/**
 * Integration tests for {@link LibchaosNetConnectionChaos} against real containers.
 *
 * <p>Exercises the full lifecycle on both glibc (debian:bookworm-slim) and musl (alpine) base
 * images: pre-start preparation, post-start rule application, config-file persistence, and cleanup.
 * Actual fault delivery (rules causing the right syscall errors) is delegated to the libchaos-net
 * library's own end-to-end suite — this test verifies the Java-side wiring.
 *
 * <p>Each test method runs against both distributions via {@link ParameterizedTest} so glibc/musl
 * binary resolution is covered without a separate test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LibchaosNetConnectionChaos — integration (debian + alpine)")
class LibchaosNetConnectionChaosIntegrationTest {

  private static final String CONFIG_PATH = "/tmp/.chaos-net.conf";
  private static final String LIBRARY_PATH = "/usr/local/lib/libchaos-net.so";
  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";

  private GenericContainer<?> container;
  private LibchaosNetConnectionChaos chaos;

  /** Builds and starts a prepared container for the given image. */
  private GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.NET).prepare(c);
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
      chaos = new LibchaosNetConnectionChaos();
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
    @DisplayName("LD_PRELOAD env var contains the libchaos-net path")
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
    @DisplayName("apply() writes the wire-format rule into the libchaos-net config file")
    void applyWritesRule(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosNetConnectionChaos();

      final RuleHandle handle =
          chaos.apply(
              container,
              NetRule.errno(
                  Endpoint.tcp4("db.example.com", 5432),
                  NetOperation.CONNECT,
                  Errno.ECONNREFUSED,
                  1.0));

      final var cat = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(cat.getStdout())
          .contains("tcp4://db.example.com:5432:connect:ERRNO:ECONNREFUSED:1.0")
          .contains("# " + handle.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("portable addLatency emits both SEND and RECV LATENCY rules")
    void addLatencyEmitsBothDirections(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosNetConnectionChaos();

      chaos.addLatency(container, "db.example.com:5432", Duration.ofMillis(150));

      final var cat = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(cat.getStdout())
          .contains("tcp4://db.example.com:5432:send:LATENCY:150")
          .contains("tcp4://db.example.com:5432:recv:LATENCY:150");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("remove(handle) deletes the matching rule from the config file")
    void removeDeletesRule(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosNetConnectionChaos();

      final RuleHandle handle =
          chaos.apply(
              container,
              NetRule.errno(Endpoint.tcp4("a", 1), NetOperation.CONNECT, Errno.ECONNREFUSED, 1.0));

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
      chaos = new LibchaosNetConnectionChaos();

      chaos.apply(
          container, NetRule.errno(Endpoint.tcp4("a", 1), NetOperation.CONNECT, Errno.EPIPE, 1.0));
      chaos.apply(
          container, NetRule.errno(Endpoint.tcp4("b", 2), NetOperation.CONNECT, Errno.EPIPE, 1.0));

      chaos.removeAll(container);

      final var cat =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true");
      assertThat(cat.getStdout()).doesNotContain("tcp4://a:1").doesNotContain("tcp4://b:2");
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
      chaos = new LibchaosNetConnectionChaos();

      final NetRule rule =
          NetRule.errno(Endpoint.tcp4("db", 5432), NetOperation.CONNECT, Errno.EPIPE, 1.0);

      assertThatThrownBy(() -> chaos.apply(container, rule))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("net")
          .hasMessageContaining("@SyscallLevelChaos");
    }
  }
}
