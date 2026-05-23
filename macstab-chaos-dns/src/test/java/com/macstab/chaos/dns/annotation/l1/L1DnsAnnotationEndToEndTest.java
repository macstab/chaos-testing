/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1;

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
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEaiagain;
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEaifail;
import com.macstab.chaos.dns.annotation.l1.wildcard.ChaosWildcardEaiagain;

/**
 * End-to-end integration test for the DNS L1 annotation path.
 *
 * <p>Exercises the full chain: L1 annotation on a fixture class → {@link L1AnnotationProcessor}
 * reflective dispatch → {@code DnsEaiTranslator} → {@code LibchaosDnsChaos.apply()} →
 * config-file write inside a real running container. Verifies both class-scope application and
 * post-remove cleanup, on both glibc and musl base images.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("L1 DNS annotations — end-to-end annotation path")
class L1DnsAnnotationEndToEndTest {

  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";
  private static final String CONF = LibchaosLib.DNS.getConfigPath();

  // ==================== Fixture classes ====================

  @ChaosForwardEaiagain
  static class WithForwardEaiagain {}

  @ChaosForwardEaifail
  static class WithForwardEaifail {}

  @ChaosWildcardEaiagain
  static class WithWildcardEaiagain {}

  // ==================== Tests ====================

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosForwardEaiagain writes getaddrinfo EAI_AGAIN rule to config and removes cleanly")
  void forwardEaiagainWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithForwardEaiagain.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("EAI_AGAIN");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EAI_AGAIN");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosForwardEaifail writes getaddrinfo EAI_FAIL rule to config and removes cleanly")
  void forwardEaifailWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithForwardEaifail.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("EAI_FAIL");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EAI_FAIL");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("@ChaosWildcardEaiagain writes wildcard EAI_AGAIN rule to config and removes cleanly")
  void wildcardEaiagainWrittenAndRemoved(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final var handles = containers(container);
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithWildcardEaiagain.class, handles, new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout()).contains("EAI_AGAIN");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after =
          container.execInContainer(
              "/bin/sh", "-c", "[ -f " + CONF + " ] && cat " + CONF + " || true");
      assertThat(after.getStdout()).doesNotContain("EAI_AGAIN");
    }
  }

  // ==================== Helpers ====================

  private static GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.DNS).prepare(c);
    c.start();
    return c;
  }

  private static List<ContainerHandle> containers(final GenericContainer<?> c) {
    return List.of(new ContainerHandle(c, "", Override.class));
  }
}
