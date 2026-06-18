/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.network;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;

@DisplayName("TcNetworkChaos - Comprehensive Tests")
class TcNetworkChaosComprehensiveTest {
  private GenericContainer<?> container;
  private TcNetworkChaos chaos;

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      if (chaos != null) chaos.reset(container);
      container.stop();
    }
  }

  @Nested
  @DisplayName("Debian Tests")
  class DebianTests {
    @ParameterizedTest
    @ValueSource(ints = {10, 50, 100, 200, 500})
    void shouldInjectLatency(int ms) throws Exception {
      container = createDebianContainer();
      chaos = new TcNetworkChaos();
      chaos.injectLatency(container, Duration.ofMillis(ms));
      var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
      assertThat(result.getStdout()).contains("netem");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.1, 0.3, 0.5, 1.0})
    void shouldInjectPacketLoss(double rate) throws Exception {
      container = createDebianContainer();
      chaos = new TcNetworkChaos();
      chaos.injectPacketLoss(container, rate);
      assertThat(container.isRunning()).isTrue();
    }

    @Test
    void shouldLimitBandwidth() throws Exception {
      container = createDebianContainer();
      chaos = new TcNetworkChaos();
      chaos.limitBandwidth(container, "1mbit");
      var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
      assertThat(result.getStdout()).contains("tbf");
    }
  }

  @Nested
  @DisplayName("Alpine Tests")
  class AlpineTests {
    @Test
    void shouldInjectLatencyWithJitter() throws Exception {
      container = createAlpineContainer();
      chaos = new TcNetworkChaos();
      chaos.injectLatencyWithJitter(container, Duration.ofMillis(50), Duration.ofMillis(10));
      var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
      assertThat(result.getStdout()).contains("netem");
    }
  }

  @Nested
  @DisplayName("Negative Tests")
  class NegativeTests {
    @ParameterizedTest
    @ValueSource(doubles = {-1.0, 1.5, 2.0})
    void shouldRejectInvalidPacketLoss(double rate) throws Exception {
      container = createDebianContainer();
      chaos = new TcNetworkChaos();
      assertThatThrownBy(() -> chaos.injectPacketLoss(container, rate))
          .hasMessageContaining("must be in [0.0, 1.0]");
    }

    @Test
    void shouldRejectNegativeLatency() throws Exception {
      container = createDebianContainer();
      chaos = new TcNetworkChaos();
      assertThatThrownBy(() -> chaos.injectLatency(container, Duration.ofMillis(-100)))
          .hasMessageContaining("must not be negative");
    }
  }

  @Nested
  @DisplayName("Cleanup Tests")
  class CleanupTests {
    @Test
    void shouldRemoveAllQdiscs() throws Exception {
      container = createDebianContainer();
      chaos = new TcNetworkChaos();
      chaos.injectLatency(container, Duration.ofMillis(50));
      chaos.reset(container);
      var result = container.execInContainer("tc", "qdisc", "show", "dev", "eth0");
      assertThat(result.getStdout()).doesNotContain("netem");
    }
  }

  private GenericContainer<?> createDebianContainer() {
    var c =
        new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    c.start();
    return c;
  }

  private GenericContainer<?> createAlpineContainer() {
    var c =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    c.start();
    return c;
  }
}
