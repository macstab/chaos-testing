/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

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
import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Integration tests for {@link LibchaosDnsChaos} against real containers.
 *
 * <p>Exercises the full lifecycle on both glibc ({@code debian:bookworm-slim}) and musl ({@code
 * alpine:3.20}) base images: pre-start preparation, post-start rule application, config-file
 * persistence, and cleanup. Actual fault delivery is delegated to the libchaos-dns library's own
 * end-to-end suite — this test verifies the Java-side wiring.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LibchaosDnsChaos — integration (debian + alpine)")
class LibchaosDnsChaosIntegrationTest {

  private static final String CONFIG_PATH = "/tmp/.chaos-dns.conf";
  private static final String LIBRARY_PATH = "/usr/local/lib/libchaos-dns.so";
  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";

  private GenericContainer<?> container;
  private LibchaosDnsChaos chaos;

  private GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.DNS).prepare(c);
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
      chaos = new LibchaosDnsChaos();
      assertThat(chaos.supports(container)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName(".so deployed at canonical path")
    void libraryDeployed(final String image) throws Exception {
      container = prepared(image);
      assertThat(container.execInContainer("test", "-f", LIBRARY_PATH).getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("LD_PRELOAD contains the libchaos-dns path")
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
    @DisplayName("apply() writes the EAI_FAIL rule into the config file")
    void applyWritesRule(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosDnsChaos();

      final RuleHandle handle =
          chaos.apply(
              container, DnsRule.eai(DnsSelector.host("api.example.com"), EaiErrno.EAI_FAIL));

      final var cat = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(cat.getStdout())
          .contains("dns://api.example.com:EAI_FAIL")
          .contains("# " + handle.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("slowResolution emits LATENCY rule")
    void slowResolution(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosDnsChaos();

      chaos.slowResolution(container, "redis.internal", Duration.ofMillis(250));

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("dns://redis.internal:LATENCY:250");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("overrideAnswer emits OVERRIDE with bracketed IPv6")
    void overrideAnswer(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosDnsChaos();

      chaos.overrideAnswer(
          container,
          "api.example.com",
          List.of(InetAddress.getByName("1.2.3.4"), InetAddress.getByName("::1")));

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("dns://api.example.com:OVERRIDE:1.2.3.4,[::1]");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("failReverse on IPv6 emits bracketed selector")
    void failReverseV6(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosDnsChaos();

      chaos.failReverse(container, "::1", EaiErrno.EAI_NONAME);

      assertThat(container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout())
          .contains("rdns://[::1]:EAI_NONAME");
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("remove(handle) deletes the rule")
    void removeOne(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosDnsChaos();

      final RuleHandle handle =
          chaos.apply(container, DnsRule.eai(DnsSelector.host("h"), EaiErrno.EAI_FAIL));

      chaos.remove(container, handle);

      final var cat =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true");
      assertThat(cat.getStdout()).doesNotContain("# " + handle.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {DEBIAN, ALPINE})
    @DisplayName("removeAll wipes every rule")
    void removeAll(final String image) throws Exception {
      container = prepared(image);
      chaos = new LibchaosDnsChaos();

      chaos.apply(container, DnsRule.eai(DnsSelector.host("a"), EaiErrno.EAI_FAIL));
      chaos.apply(container, DnsRule.eai(DnsSelector.host("b"), EaiErrno.EAI_FAIL));

      chaos.removeAll(container);

      final var cat =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONFIG_PATH + " ] && cat " + CONFIG_PATH + " || true");
      assertThat(cat.getStdout()).doesNotContain("dns://a:").doesNotContain("dns://b:");
    }
  }

  @Nested
  @DisplayName("pre-flight gate")
  class PreflightGate {

    @Test
    @DisplayName("advanced verb on an unprepared container raises LibchaosNotPreparedException")
    void unpreparedThrows() {
      container =
          new GenericContainer<>(DockerImageName.parse(DEBIAN)).withCommand("sleep", "infinity");
      container.start();
      chaos = new LibchaosDnsChaos();

      assertThatThrownBy(
              () -> chaos.apply(container, DnsRule.eai(DnsSelector.host("h"), EaiErrno.EAI_FAIL)))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("dns")
          .hasMessageContaining("@SyscallLevelChaos");
    }
  }
}
