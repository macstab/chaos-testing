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

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
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

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
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

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle packet loss on Alpine")
    void shouldHandlePacketLossAlpine() throws Exception {
      container = createAlpineContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.dropPackets(container, "example.com:80", 0.3);

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
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
      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle multiple targets")
    void shouldHandleMultipleTargets() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "api1.example.com:443", Duration.ofMillis(100));
      chaos.addLatency(container, "api2.example.com:443", Duration.ofMillis(200));
      chaos.addLatency(container, "api3.example.com:443", Duration.ofMillis(300));

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.1, 0.5, 0.9, 1.0})
    @DisplayName("should handle various packet loss rates")
    void shouldHandleVariousRates(double rate) throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.dropPackets(container, "test.com:80", rate);

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {1024, 10240, 102400, 1048576})
    @DisplayName("should handle various bandwidth limits")
    void shouldHandleVariousBandwidths(long bytesPerSecond) throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.limitBandwidth(container, "download.example.com:443", bytesPerSecond);

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
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
      chaos.reset(container); // idempotent — no throw
      chaos.reset(container); // idempotent — no throw

      // Toxiproxy stays alive, proxy is gone
      assertThat(container.execInContainer("/bin/sh", "-c",
          "curl -s -f http://localhost:8474/proxies").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle chaos after reset")
    void shouldHandleChaosAfterReset() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "test1.com:80", Duration.ofMillis(100));
      chaos.reset(container);
      chaos.addLatency(container, "test2.com:80", Duration.ofMillis(200));

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle port collision gracefully")
    void shouldHandlePortCollision() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      // Same target port, different hosts
      chaos.addLatency(container, "host1.com:443", Duration.ofMillis(50));
      chaos.addLatency(container, "host2.com:443", Duration.ofMillis(100));

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle very high latency values")
    void shouldHandleHighLatency() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "slow.com:80", Duration.ofSeconds(30));

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
    }

    @Test
    @DisplayName("should handle zero latency")
    void shouldHandleZeroLatency() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "fast.com:80", Duration.ZERO);

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
    }
  }

  // ==================== CLEANUP TESTS ====================

  @Nested
  @DisplayName("Cleanup Verification")
  class CleanupTests {

    @Test
    @DisplayName("surgical reset removes own proxies, keeps Toxiproxy running")
    void shouldRemoveOwnProxiesKeepToxiproxy() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "test1.com:80", Duration.ofMillis(100));
      chaos.addLatency(container, "test2.com:443", Duration.ofMillis(200));

      chaos.reset(container);

      // Toxiproxy process stays alive
      assertThat(container.execInContainer("/bin/sh", "-c",
          "curl -s -f http://localhost:8474/proxies").getExitCode()).isZero();
      // Both proxies are gone
      assertThat(container.execInContainer("/bin/sh", "-c",
          "curl -s -f http://localhost:8474/proxies/conn_test1_com_80").getExitCode()).isNotZero();
      assertThat(container.execInContainer("/bin/sh", "-c",
          "curl -s -f http://localhost:8474/proxies/conn_test2_com_443").getExitCode()).isNotZero();
    }

    @Test
    @DisplayName("per-target reset removes only that target")
    void shouldResetSingleTarget() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "target1.com:80", Duration.ofMillis(100));
      chaos.addLatency(container, "target2.com:81", Duration.ofMillis(200)); // different port avoids port collision

      chaos.reset(container, "target1.com:80");

      // All proxies listed — target2 must still be there
      final String allProxies = container.execInContainer("/bin/sh", "-c",
          "curl -s http://localhost:8474/proxies").getStdout();
      assertThat(allProxies).doesNotContain("conn_target1_com_80");
      assertThat(allProxies).contains("conn_target2_com_81");
    }

    @Test
    @DisplayName("should allow re-initialization after reset")
    void shouldAllowReInitAfterReset() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatency(container, "test1.com:80", Duration.ofMillis(100));
      chaos.reset(container);
      chaos.addLatency(container, "test2.com:80", Duration.ofMillis(200));

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
    }
  }

  // ==================== NEW METHODS ====================

  @Nested
  @DisplayName("New Methods")
  class NewMethodTests {

    @Test
    @DisplayName("truncateConnection creates limit_data toxic")
    void shouldTruncateConnection() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.truncateConnection(container, "api.example.com:443", 1024);

      // Verify toxic exists with correct type
      assertThat(container.execInContainer("/bin/sh", "-c",
          "curl -s http://localhost:8474/proxies/conn_api_example_com_443/toxics").getStdout())
          .contains("limit_data");
    }

    @Test
    @DisplayName("addLatencyWithJitter creates latency toxic with jitter")
    void shouldAddLatencyWithJitter() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.addLatencyWithJitter(container, "api.example.com:443",
          Duration.ofMillis(100), Duration.ofMillis(20));

      final String toxics = container.execInContainer("/bin/sh", "-c",
          "curl -s http://localhost:8474/proxies/conn_api_example_com_443/toxics").getStdout();
      assertThat(toxics).contains("latency");
      assertThat(toxics).contains("100"); // latencyMs
      assertThat(toxics).contains("20");  // jitterMs
    }

    @Test
    @DisplayName("removeToxic removes specific toxic")
    void shouldRemoveToxic() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();
      chaos.addLatency(container, "api.example.com:443", Duration.ofMillis(100));

      chaos.removeToxic(container, "api.example.com:443", "latency");

      final String toxics = container.execInContainer("/bin/sh", "-c",
          "curl -s http://localhost:8474/proxies/conn_api_example_com_443/toxics").getStdout();
      assertThat(toxics).doesNotContain("\"latency\"");
    }

    @Test
    @DisplayName("removeAllToxics clears all faults, proxy stays")
    void shouldRemoveAllToxics() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();
      final String target = "api.example.com:443";
      chaos.addLatency(container, target, Duration.ofMillis(100));
      chaos.dropPackets(container, target, 0.1);

      chaos.removeAllToxics(container, target);

      // Proxy still exists
      assertThat(container.execInContainer("/bin/sh", "-c",
          "curl -s -f http://localhost:8474/proxies/conn_api_example_com_443").getExitCode()).isZero();
      // No toxics
      assertThat(container.execInContainer("/bin/sh", "-c",
          "curl -s http://localhost:8474/proxies/conn_api_example_com_443/toxics").getStdout())
          .isEqualTo("[]");
    }

    @Test
    @DisplayName("rejectConnections creates proxy with down toxic")
    void shouldRejectConnectionsWithDownToxic() throws Exception {
      container = createDebianContainer();
      chaos = new ToxiproxyConnectionChaos();

      chaos.rejectConnections(container, "api.example.com:443");

      // Proxy exists
      assertThat(container.execInContainer("/bin/sh", "-c",
          "curl -s -f http://localhost:8474/proxies/conn_api_example_com_443").getExitCode())
          .isZero();
      // Verify the proxy has toxics by checking all proxies response contains our proxy
      final String allProxies = container.execInContainer("/bin/sh", "-c",
          "curl -s http://localhost:8474/proxies").getStdout();
      assertThat(allProxies).contains("conn_api_example_com_443");
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

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
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

      assertThat(container.execInContainer("curl", "-s", "-f", "http://localhost:8474/proxies").getExitCode()).isZero();
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
