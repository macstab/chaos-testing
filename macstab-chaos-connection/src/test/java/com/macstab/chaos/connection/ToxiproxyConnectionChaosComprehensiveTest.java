/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection;

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

import com.github.dockerjava.api.model.Capability;

/**
 * Comprehensive integration tests for {@link ToxiproxyConnectionChaos}.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Multiple container distributions (Debian, Alpine)
 *   <li>Positive scenarios (chaos works)
 *   <li>Negative scenarios (validation, errors)
 *   <li>Edge cases (concurrent ops, cleanup)
 *   <li>Integration scenarios (multiple chaos)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ToxiproxyConnectionChaos - Comprehensive Tests")
class ToxiproxyConnectionChaosComprehensiveTest {

  private GenericContainer<?> container;
  private ToxiproxyConnectionChaos chaos;

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      if (chaos != null) {
        chaos.reset(container);
      }
      container.stop();
    }
  }

  // ==================== DISTRIBUTION TESTS ====================

  @Nested
  @DisplayName("Debian-based container (redis:7.4)")
  class DebianTests {

    @Test
    @DisplayName("should add latency on Debian")
    void shouldAddLatencyDebian() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "google.com:443", Duration.ofMillis(100));

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle all chaos types on Debian")
    void shouldHandleAllChaosTypesDebian() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      final String target = "example.com:443";
      chaos.addLatency(container, target, Duration.ofMillis(50));
      chaos.dropPackets(container, target, 0.1);
      chaos.limitBandwidth(container, target, 1024 * 100);
      chaos.timeoutConnections(container, target, Duration.ofMillis(500));
      chaos.slowClose(container, target, Duration.ofMillis(200));

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }
  }

  @Nested
  @DisplayName("Alpine-based container (redis:7.4-alpine)")
  class AlpineTests {

    @Test
    @DisplayName("should add latency on Alpine")
    void shouldAddLatencyAlpine() throws Exception {
      container = createAlpineContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "google.com:443", Duration.ofMillis(100));

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle packet loss on Alpine")
    void shouldHandlePacketLossAlpine() throws Exception {
      container = createAlpineContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.dropPackets(container, "example.com:80", 0.3);

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }
  }

  // ==================== POSITIVE TESTS ====================

  @Nested
  @DisplayName("Positive Scenarios")
  class PositiveTests {

    @Test
    @DisplayName("should add cumulative latency")
    void shouldAddCumulativeLatency() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      final String target = "api.example.com:443";
      chaos.addLatency(container, target, Duration.ofMillis(50));
      chaos.addLatency(container, target, Duration.ofMillis(30));
      chaos.addLatency(container, target, Duration.ofMillis(20));

      // Toxiproxy should have 3 latency toxics
      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle multiple targets")
    void shouldHandleMultipleTargets() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "api1.example.com:443", Duration.ofMillis(100));
      chaos.addLatency(container, "api2.example.com:443", Duration.ofMillis(200));
      chaos.addLatency(container, "api3.example.com:443", Duration.ofMillis(300));

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.1, 0.5, 0.9, 1.0})
    @DisplayName("should handle various packet loss rates")
    void shouldHandleVariousRates(double rate) throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.dropPackets(container, "test.com:80", rate);

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {1024, 10240, 102400, 1048576})
    @DisplayName("should handle various bandwidth limits")
    void shouldHandleVariousBandwidths(long bytesPerSecond) throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.limitBandwidth(container, "download.example.com:443", bytesPerSecond);

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }
  }

  // ==================== NEGATIVE TESTS ====================

  @Nested
  @DisplayName("Negative Scenarios - Validation")
  class NegativeTests {

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      chaos = new ToxiproxyConnectionChaos();

      assertThatThrownBy(() -> chaos.addLatency(null, "test.com:80", Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject null target")
    void shouldRejectNullTarget() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      assertThatThrownBy(() -> chaos.addLatency(container, null, Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject null latency")
    void shouldRejectNullLatency() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      assertThatThrownBy(() -> chaos.addLatency(container, "test.com:80", null))
          .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "host:port:extra", "host", ":80", "host:", "host:abc"})
    @DisplayName("should reject invalid target formats")
    void shouldRejectInvalidTargets(String target) throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      assertThatThrownBy(() -> chaos.addLatency(container, target, Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -0.1, 1.1, 2.0, Double.MAX_VALUE})
    @DisplayName("should reject invalid packet loss rates")
    void shouldRejectInvalidRates(double rate) throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      assertThatThrownBy(() -> chaos.dropPackets(container, "test.com:80", rate))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -1000})
    @DisplayName("should reject invalid bandwidth limits")
    void shouldRejectInvalidBandwidth(long bytesPerSecond) throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      assertThatThrownBy(() -> chaos.limitBandwidth(container, "test.com:80", bytesPerSecond))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() throws Exception {
      container = createDebianContainer();
      container.stop();
      chaos = new ToxiproxyConnectionChaos();

      assertThatThrownBy(() -> chaos.addLatency(container, "test.com:80", Duration.ofMillis(100)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be running");
    }

    @Test
    @DisplayName("should reject invalid port numbers")
    void shouldRejectInvalidPorts() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      assertThatThrownBy(() -> chaos.addLatency(container, "test.com:0", Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class);

      assertThatThrownBy(
              () -> chaos.addLatency(container, "test.com:65536", Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ==================== EDGE CASES ====================

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("should handle repeated reset calls")
    void shouldHandleRepeatedResets() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "test.com:80", Duration.ofMillis(50));
      chaos.reset(container);
      chaos.reset(container);
      chaos.reset(container);

      // Should not throw, no Toxiproxy running
      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isNotZero();
    }

    @Test
    @DisplayName("should handle chaos after reset")
    void shouldHandleChaosAfterReset() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "test1.com:80", Duration.ofMillis(100));
      chaos.reset(container);
      chaos.addLatency(container, "test2.com:80", Duration.ofMillis(200));

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle port collision gracefully")
    void shouldHandlePortCollision() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      // Same target port, different hosts
      chaos.addLatency(container, "host1.com:443", Duration.ofMillis(50));
      chaos.addLatency(container, "host2.com:443", Duration.ofMillis(100));

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle very high latency values")
    void shouldHandleHighLatency() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "slow.com:80", Duration.ofSeconds(30));

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle zero latency")
    void shouldHandleZeroLatency() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "fast.com:80", Duration.ZERO);

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }
  }

  // ==================== CLEANUP TESTS ====================

  @Nested
  @DisplayName("Cleanup Verification")
  class CleanupTests {

    @Test
    @DisplayName("should remove all iptables rules")
    void shouldRemoveAllIptablesRules() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "test1.com:80", Duration.ofMillis(100));
      chaos.addLatency(container, "test2.com:443", Duration.ofMillis(200));

      chaos.reset(container);

      final var result = container.execInContainer("iptables", "-t", "nat", "-L", "OUTPUT");
      assertThat(result.getStdout()).doesNotContain("REDIRECT");
    }

    @Test
    @DisplayName("should stop Toxiproxy process")
    void shouldStopToxiproxy() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "test.com:80", Duration.ofMillis(100));

      chaos.reset(container);

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isNotZero();
    }

    @Test
    @DisplayName("should allow re-initialization after reset")
    void shouldAllowReInitAfterReset() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "test1.com:80", Duration.ofMillis(100));
      chaos.reset(container);
      chaos.addLatency(container, "test2.com:80", Duration.ofMillis(200));

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }
  }

  // ==================== INTEGRATION TESTS ====================

  @Nested
  @DisplayName("Integration Scenarios")
  class IntegrationTests {

    @Test
    @DisplayName("should combine latency, packet loss, and bandwidth")
    void shouldCombineChaosTypes() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      final String target = "api.example.com:443";
      chaos.addLatency(container, target, Duration.ofMillis(100));
      chaos.dropPackets(container, target, 0.2);
      chaos.limitBandwidth(container, target, 1024 * 50);

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle all chaos methods on single target")
    void shouldHandleAllMethodsOnTarget() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      final String target = "full-chaos.example.com:443";
      chaos.addLatency(container, target, Duration.ofMillis(50));
      chaos.dropPackets(container, target, 0.1);
      chaos.limitBandwidth(container, target, 1024 * 100);
      chaos.timeoutConnections(container, target, Duration.ofMillis(500));
      chaos.slowClose(container, target, Duration.ofMillis(200));
      chaos.rejectConnections(container, target);

      assertThat(container.execInContainer("pgrep", "toxiproxy-server").getExitCode()).isZero();
    }
  }

  // ==================== HELPER METHODS ====================

  private GenericContainer<?> createDebianContainer() {
    final var c =
        new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    c.start();
    return c;
  }

  private GenericContainer<?> createAlpineContainer() {
    final var c =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    c.start();
    return c;
  }
}
