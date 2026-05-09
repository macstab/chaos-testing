/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/**
 * Integration tests for {@link LibchaosTransport} using the libchaos-io binary (vendored in
 * macstab-chaos-disk resources).
 *
 * <p>Verifies .so deployment, LD_PRELOAD wiring, and config file operations against a real running
 * container. Actual fault delivery is covered by end-to-end tests.
 */
@DisplayName("LibchaosTransport — integration (libchaos-io)")
class LibchaosTransportIntegrationTest {

  private static final String CONFIG_PATH = "/tmp/.chaos-io.conf";
  private static final String LIBRARY_PATH = "/usr/local/lib/libchaos-io.so";

  private GenericContainer<?> container;
  private LibchaosTransport transport;

  @BeforeEach
  void setUp() {
    transport = new LibchaosTransport(LibchaosLib.IO);
    container =
        new GenericContainer<>(DockerImageName.parse("debian:bookworm-slim"))
            .withCommand("sleep", "infinity");
    transport.prepare(container);
    container.start();
  }

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      container.stop();
    }
  }

  @Nested
  @DisplayName("prepare")
  class Prepare {

    @Test
    @DisplayName("sets active label on container")
    void setsLabel() {
      assertThat(transport.isActive(container)).isTrue();
    }

    @Test
    @DisplayName("deploys .so to expected path inside container")
    void deploysLibrary() throws Exception {
      final var result = container.execInContainer("test", "-f", LIBRARY_PATH);
      assertThat(result.getExitCode()).isZero();
    }

    @Test
    @DisplayName("idempotent — calling twice does not add duplicate labels")
    void idempotent() {
      transport.prepare(container);
      final long count =
          container.getLabels().keySet().stream()
              .filter(k -> k.equals("macstab.chaos.io.active"))
              .count();
      assertThat(count).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("addRule")
  class AddRule {

    @Test
    @DisplayName("rule appears in config file with owner comment")
    void ruleWrittenToConfig() throws Exception {
      transport.addRule(container, "disk", "/data:write:ERRNO:EIO:0.3");
      final var result = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(result.getStdout()).contains("/data:write:ERRNO:EIO:0.3").contains("# disk");
    }

    @Test
    @DisplayName("multiple addRule calls accumulate rules")
    void multipleRulesAccumulate() throws Exception {
      transport.addRule(container, "disk", "r1:write:ERRNO:EIO:0.1");
      transport.addRule(container, "disk", "r2:read:LATENCY:50");
      final var result = container.execInContainer("/bin/sh", "-c", "wc -l < " + CONFIG_PATH);
      assertThat(Integer.parseInt(result.getStdout().trim())).isGreaterThanOrEqualTo(2);
    }
  }

  @Nested
  @DisplayName("addRules")
  class AddRules {

    @Test
    @DisplayName("all rules written in one shot")
    void allRulesWritten() throws Exception {
      transport.addRules(
          container, "disk", List.of("/data:write:ERRNO:EIO:0.5", "/data:read:LATENCY:100"));
      final var result = container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH);
      assertThat(result.getStdout())
          .contains("/data:write:ERRNO:EIO:0.5")
          .contains("/data:read:LATENCY:100");
    }
  }

  @Nested
  @DisplayName("removeRules")
  class RemoveRules {

    @Test
    @DisplayName("removes only the specified owner's rules")
    void removesOwnerRulesOnly() throws Exception {
      transport.addRule(container, "disk", "/data:write:ERRNO:EIO:0.3");
      transport.addRule(container, "fs", "/mnt:read:LATENCY:20");
      transport.removeRules(container, "disk");
      final var result =
          container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH + " 2>/dev/null || echo");
      assertThat(result.getStdout()).doesNotContain("# disk");
      assertThat(result.getStdout()).contains("# fs");
    }
  }

  @Nested
  @DisplayName("clearRules")
  class ClearRules {

    @Test
    @DisplayName("config file is absent after clear")
    void configAbsentAfterClear() throws Exception {
      transport.addRule(container, "disk", "/data:write:ERRNO:EIO:0.3");
      transport.clearRules(container);
      final var result = container.execInContainer("test", "-f", CONFIG_PATH);
      assertThat(result.getExitCode()).isNotZero();
    }
  }
}
