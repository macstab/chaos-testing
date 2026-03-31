/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;

/**
 * Integration tests for {@link ToxiproxyConnectionChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class ToxiproxyConnectionChaosTest {

  private GenericContainer<?> container;
  private ToxiproxyConnectionChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    container.start();

    chaos = new ToxiproxyConnectionChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should add latency to target")
  void shouldAddLatency() throws Exception {
    final String target = "google.com:443";
    final Duration latency = Duration.ofMillis(100);

    chaos.addLatency(container, target, latency);

    // Verify Toxiproxy started
    assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should drop packets to target")
  void shouldDropPackets() throws Exception {
    final String target = "google.com:443";
    final double rate = 0.3;

    chaos.dropPackets(container, target, rate);

    assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should limit bandwidth to target")
  void shouldLimitBandwidth() throws Exception {
    final String target = "google.com:443";
    final long bytesPerSecond = 1024 * 100; // 100KB/s

    chaos.limitBandwidth(container, target, bytesPerSecond);

    assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should timeout connections to target")
  void shouldTimeoutConnections() throws Exception {
    final String target = "google.com:443";
    final Duration timeout = Duration.ofMillis(500);

    chaos.timeoutConnections(container, target, timeout);

    assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should slow close connections to target")
  void shouldSlowClose() throws Exception {
    final String target = "google.com:443";
    final Duration delay = Duration.ofMillis(1000);

    chaos.slowClose(container, target, delay);

    assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should reject connections to target")
  void shouldRejectConnections() throws Exception {
    final String target = "google.com:443";

    chaos.rejectConnections(container, target);

    assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should reset chaos")
  void shouldReset() throws Exception {
    final String target = "google.com:443";
    chaos.addLatency(container, target, Duration.ofMillis(50));

    chaos.reset(container);

    // Toxiproxy should be stopped
    assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isNotZero();
  }

  @Test
  @DisplayName("should be supported")
  void shouldBeSupported() throws Exception {
    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should reject invalid target format")
  void shouldRejectInvalidTarget() throws Exception {
    assertThatThrownBy(() -> chaos.addLatency(container, "invalid", Duration.ofMillis(100)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("host:port");
  }

  @Test
  @DisplayName("should reject invalid packet loss rate")
  void shouldRejectInvalidRate() throws Exception {
    assertThatThrownBy(() -> chaos.dropPackets(container, "google.com:443", 1.5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be in [0.0, 1.0]");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.addLatency(container, "google.com:443", Duration.ofMillis(100)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be running");
  }

  @Test
  @DisplayName("should handle multiple chaos operations on same target")
  void shouldHandleMultipleChaosOperations() throws Exception {
    final String target = "google.com:443";

    chaos.addLatency(container, target, Duration.ofMillis(50));
    chaos.dropPackets(container, target, 0.1);
    chaos.limitBandwidth(container, target, 1024 * 50);

    assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
  }
}
