/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.network;

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
 * Integration tests for {@link TcNetworkChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class TcNetworkChaosTest {

  private GenericContainer<?> container;
  private TcNetworkChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    container.start();

    chaos = new TcNetworkChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should inject latency")
  void shouldInjectLatency() throws Exception {
    final Duration latency = Duration.ofMillis(100);

    chaos.injectLatency(container, latency);

    final var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
    assertThat(result.getStdout()).contains("netem");
  }

  @Test
  @DisplayName("should inject latency with jitter")
  void shouldInjectLatencyWithJitter() throws Exception {
    final Duration latency = Duration.ofMillis(50);
    final Duration jitter = Duration.ofMillis(10);

    chaos.injectLatencyWithJitter(container, latency, jitter);

    final var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
    assertThat(result.getStdout()).contains("netem");
  }

  @Test
  @DisplayName("should inject packet loss")
  void shouldInjectPacketLoss() throws Exception {
    final double percentage = 0.3;

    chaos.injectPacketLoss(container, percentage);

    final var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
    assertThat(result.getStdout()).contains("netem");
  }

  @Test
  @DisplayName("should inject correlated packet loss")
  void shouldInjectCorrelatedPacketLoss() throws Exception {
    final double percentage = 0.2;
    final double correlation = 0.5;

    chaos.injectCorrelatedPacketLoss(container, percentage, correlation);

    final var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
    assertThat(result.getStdout()).contains("netem");
  }

  @Test
  @DisplayName("should limit bandwidth")
  void shouldLimitBandwidth() throws Exception {
    final String rate = "1mbit";

    chaos.limitBandwidth(container, rate);

    final var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
    assertThat(result.getStdout()).contains("tbf");
  }

  @Test
  @DisplayName("should partition from target")
  void shouldPartitionFromTarget() throws Exception {
    final var target = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    target.start();

    try {
      chaos.partitionFrom(container, target);

      final var result = container.execInContainer("iptables", "-L", "OUTPUT");
      assertThat(result.getStdout()).contains("DROP");
    } finally {
      target.stop();
    }
  }

  @Test
  @DisplayName("should reset network chaos")
  void shouldReset() throws Exception {
    chaos.injectLatency(container, Duration.ofMillis(50));

    chaos.reset(container);

    final var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
    assertThat(result.getStdout()).doesNotContain("netem");
  }

  @Test
  @DisplayName("should be supported")
  void shouldBeSupported() throws Exception {
    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should reject negative latency")
  void shouldRejectNegativeLatency() throws Exception {
    assertThatThrownBy(() -> chaos.injectLatency(container, Duration.ofMillis(-100)))
        .hasMessageContaining("must not be negative");
  }

  @Test
  @DisplayName("should reject invalid packet loss rate")
  void shouldRejectInvalidRate() throws Exception {
    assertThatThrownBy(() -> chaos.injectPacketLoss(container, 1.5))
        .hasMessageContaining("must be in [0.0, 1.0]");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.injectLatency(container, Duration.ofMillis(100)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be running");
  }
}
