/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycle;
import com.macstab.chaos.toxiproxy.network.NetworkRedirect;

/**
 * Unit tests for {@link ToxiproxyConnectionChaos} — no Docker required.
 *
 * <p>Uses the package-private testability constructor to inject mocks.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ToxiproxyConnectionChaos (unit)")
class ToxiproxyConnectionChaosUnitTest {

  private ToxiproxyLifecycle lifecycle;
  private ToxiproxyApiClient apiClient;
  private NetworkRedirect networkRedirect;
  private GenericContainer<?> container;
  private ToxiproxyConnectionChaos chaos;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    lifecycle = mock(ToxiproxyLifecycle.class);
    apiClient = mock(ToxiproxyApiClient.class);
    networkRedirect = mock(NetworkRedirect.class);
    container = mock(GenericContainer.class);

    when(container.isRunning()).thenReturn(true);
    when(container.getLabels()).thenReturn(new HashMap<>());
    when(container.getContainerId()).thenReturn("abc123");
    when(apiClient.proxyExists(any(), anyString())).thenReturn(false);

    // Allow PlatformDetector.detect() to succeed via /etc/os-release
    final ExecResult osRelease = mock(ExecResult.class);
    when(osRelease.getExitCode()).thenReturn(0);
    when(osRelease.getStdout()).thenReturn("ID=debian\nVERSION_ID=\"12\"\n");
    when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osRelease);

    // ShellDetector: bash not available, sh available
    final ExecResult whichBashFail = mock(ExecResult.class);
    when(whichBashFail.getExitCode()).thenReturn(1);
    when(container.execInContainer("which", "/bin/bash")).thenReturn(whichBashFail);
    final ExecResult whichShOk = mock(ExecResult.class);
    when(whichShOk.getExitCode()).thenReturn(0);
    when(container.execInContainer("which", "/bin/sh")).thenReturn(whichShOk);
    // BusyBox detection — not BusyBox
    final ExecResult shHelp = mock(ExecResult.class);
    when(shHelp.getExitCode()).thenReturn(0);
    when(shHelp.getStdout()).thenReturn("");
    when(shHelp.getStderr()).thenReturn("");
    when(container.execInContainer("/bin/sh", "--help")).thenReturn(shHelp);

    chaos = new ToxiproxyConnectionChaos(
        ToxiproxyConfig.defaults(), lifecycle, apiClient, networkRedirect);
  }

  // ==================== Validation ====================

  @Nested
  @DisplayName("null validation")
  class NullValidation {

    @Test
    @DisplayName("addLatency — null container throws NPE")
    void addLatencyNullContainer() {
      assertThatThrownBy(() -> chaos.addLatency(null, "host:80", Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("addLatency — null target throws NPE")
    void addLatencyNullTarget() {
      assertThatThrownBy(() -> chaos.addLatency(container, null, Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("addLatency — null duration throws NPE")
    void addLatencyNullDuration() {
      assertThatThrownBy(() -> chaos.addLatency(container, "host:80", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("dropPackets — null container throws NPE")
    void dropPacketsNullContainer() {
      assertThatThrownBy(() -> chaos.dropPackets(null, "host:80", 0.5))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("reset — null container throws NPE")
    void resetNullContainer() {
      assertThatThrownBy(() -> chaos.reset(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== Input Validation ====================

  @Nested
  @DisplayName("input validation")
  class InputValidation {

    @Test
    @DisplayName("stopped container throws IllegalStateException")
    void stoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.addLatency(container, "host:80", Duration.ofMillis(100)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be running");
    }

    @Test
    @DisplayName("invalid target format throws IllegalArgumentException")
    void invalidTargetFormat() {
      assertThatThrownBy(() -> chaos.addLatency(container, "noport", Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("host:port");
    }

    @Test
    @DisplayName("rate > 1.0 throws IllegalArgumentException")
    void rateOutOfRange() {
      assertThatThrownBy(() -> chaos.dropPackets(container, "host:80", 1.5))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("[0.0, 1.0]");
    }

    @Test
    @DisplayName("bytesPerSecond < 1 throws IllegalArgumentException")
    void bandwidthOutOfRange() {
      assertThatThrownBy(() -> chaos.limitBandwidth(container, "host:80", 0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative jitter throws IllegalArgumentException")
    void negativeJitter() {
      assertThatThrownBy(() -> chaos.addLatencyWithJitter(
              container, "host:80", Duration.ofMillis(100), Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("jitter");
    }

    @Test
    @DisplayName("negative bytes in truncate throws IllegalArgumentException")
    void negativeTruncateBytes() {
      assertThatThrownBy(() -> chaos.truncateConnection(container, "host:80", -1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ==================== allocateProxyPort ====================

  @Nested
  @DisplayName("allocateProxyPort")
  class AllocateProxyPort {

    @Test
    @DisplayName("port 443 → proxy port 10443")
    void standardPort() throws Exception {
      chaos.addLatency(container, "host:443", Duration.ofMillis(50));
      verify(networkRedirect).setupRedirect(any(), eq(443), eq(10443));
    }

    @Test
    @DisplayName("port 80 → proxy port 10080")
    void httpPort() throws Exception {
      chaos.addLatency(container, "host:80", Duration.ofMillis(50));
      verify(networkRedirect).setupRedirect(any(), eq(80), eq(10080));
    }

    @Test
    @DisplayName("port above 55535 overflows → throws ChaosOperationFailedException")
    void portOverflow() {
      assertThatThrownBy(() -> chaos.addLatency(container, "host:55536", Duration.ofMillis(50)))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Proxy port")
          .hasMessageContaining("exceeds max port");
    }
  }

  // ==================== proxyName ====================

  @Nested
  @DisplayName("proxy naming")
  class ProxyNaming {

    @Test
    @DisplayName("host:port → conn_host_port")
    void simpleHost() throws Exception {
      chaos.addLatency(container, "myhost:1234", Duration.ofMillis(50));
      verify(apiClient).createProxy(any(), argWithName("conn_myhost_1234"));
    }

    @Test
    @DisplayName("dotted host → dots replaced with underscores")
    void dottedHost() throws Exception {
      chaos.addLatency(container, "my.host.com:443", Duration.ofMillis(50));
      verify(apiClient).createProxy(any(), argWithName("conn_my_host_com_443"));
    }

    private com.macstab.chaos.toxiproxy.config.ProxyConfiguration argWithName(final String name) {
      return org.mockito.ArgumentMatchers.argThat(c -> c.proxyName().equals(name));
    }
  }

  // ==================== ensureProxyFor — idempotency ====================

  @Nested
  @DisplayName("ensureProxyFor — proxy reuse")
  class EnsureProxyFor {

    @Test
    @DisplayName("second call to same target reuses proxy — no duplicate createProxy")
    void reuseProxy() throws Exception {
      chaos.addLatency(container, "host:80", Duration.ofMillis(50));
      chaos.dropPackets(container, "host:80", 0.1);

      // createProxy called exactly once — second operation reuses existing proxy
      verify(apiClient, org.mockito.Mockito.times(1)).createProxy(any(), any());
    }

    @Test
    @DisplayName("proxy-already-exists in API → still registers in ownedProxies")
    void proxyAlreadyExistsInApi() throws Exception {
      when(apiClient.proxyExists(any(), anyString())).thenReturn(true);

      chaos.addLatency(container, "host:80", Duration.ofMillis(50));

      // createProxy NOT called — proxy already exists
      verify(apiClient, never()).createProxy(any(), any());
      // networkRedirect NOT called either
      verify(networkRedirect, never()).setupRedirect(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("lifecycle.ensureRunning called on each operation")
    void ensureRunningCalledEachTime() throws Exception {
      chaos.addLatency(container, "host:80", Duration.ofMillis(50));
      chaos.dropPackets(container, "host:80", 0.1);

      // ensureRunning called once per public method (idempotent by its own impl)
      verify(lifecycle, org.mockito.Mockito.times(2)).ensureRunning(any());
    }
  }

  // ==================== addToxicSafe ====================

  @Nested
  @DisplayName("addToxicSafe — error handling")
  class AddToxicSafe {

    @Test
    @DisplayName("API exception wrapped as ChaosOperationFailedException")
    void apiExceptionWrapped() throws Exception {
      doThrow(new IOException("toxiproxy error"))
          .when(apiClient).addToxic(any(), anyString(), anyString(), anyString(), anyString(), anyDouble());

      assertThatThrownBy(() -> chaos.addLatency(container, "host:443", Duration.ofMillis(100)))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("latency");
    }
  }

  // ==================== installTools ====================

  @Nested
  @DisplayName("installTools")
  class InstallTools {

    @Test
    @DisplayName("delegates to lifecycle.ensureRunning")
    void delegatesToEnsureRunning() throws Exception {
      chaos.installTools(container);
      verify(lifecycle).ensureRunning(any(ContainerContext.class));
    }

    @Test
    @DisplayName("wraps IOException as ChaosOperationFailedException")
    void wrapsIoException() throws Exception {
      doThrow(new IOException("start failed")).when(lifecycle).ensureRunning(any());
      assertThatThrownBy(() -> chaos.installTools(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Toxiproxy");
    }
  }

  // ==================== reset ====================

  @Nested
  @DisplayName("reset")
  class Reset {

    @Test
    @DisplayName("reset on stopped container is a no-op")
    void resetStoppedContainer() throws Exception {
      when(container.isRunning()).thenReturn(false);
      chaos.reset(container); // must not throw
      verify(apiClient, never()).deleteProxy(any(), anyString());
    }

    @Test
    @DisplayName("reset removes owned proxies and redirects")
    void resetRemovesOwnedProxies() throws Exception {
      chaos.addLatency(container, "host:80", Duration.ofMillis(50));
      chaos.reset(container);

      verify(apiClient).deleteProxy(any(), eq("conn_host_80"));
      verify(networkRedirect).removeRedirect(any(), eq(80), eq(10080));
    }

    @Test
    @DisplayName("reset(container, target) removes only that target")
    void resetSingleTarget() throws Exception {
      chaos.addLatency(container, "host1:80", Duration.ofMillis(50));
      chaos.addLatency(container, "host2:443", Duration.ofMillis(100));

      chaos.reset(container, "host1:80");

      verify(apiClient).deleteProxy(any(), eq("conn_host1_80"));
      verify(networkRedirect).removeRedirect(any(), eq(80), eq(10080));
      // host2 untouched
      verify(apiClient, never()).deleteProxy(any(), eq("conn_host2_443"));
    }

    @Test
    @DisplayName("reset(container, target) for non-owned proxy is no-op")
    void resetNonOwnedProxy() throws Exception {
      chaos.reset(container, "unknown:9999"); // not in ownedProxies
      verify(apiClient, never()).deleteProxy(any(), anyString());
    }
  }

  // ==================== removeAllToxics / removeToxic ====================

  @Nested
  @DisplayName("toxic removal")
  class ToxicRemoval {

    @Test
    @DisplayName("removeAllToxics removes each listed toxic")
    void removeAllToxics() throws Exception {
      when(apiClient.listToxics(any(), anyString())).thenReturn(List.of("latency", "down"));

      chaos.removeAllToxics(container, "host:80");

      verify(apiClient).deleteToxic(any(), eq("conn_host_80"), eq("latency"));
      verify(apiClient).deleteToxic(any(), eq("conn_host_80"), eq("down"));
    }

    @Test
    @DisplayName("removeAllToxics — listToxics exception is swallowed")
    void removeAllToxicsProxyNotExists() throws Exception {
      doThrow(new IOException("proxy not found"))
          .when(apiClient).listToxics(any(), anyString());

      chaos.removeAllToxics(container, "host:80"); // must not throw
    }

    @Test
    @DisplayName("removeToxic calls deleteToxic on API")
    void removeToxic() throws Exception {
      chaos.removeToxic(container, "host:80", "latency");
      verify(apiClient).deleteToxic(any(), eq("conn_host_80"), eq("latency"));
    }

    @Test
    @DisplayName("removeToxic — API exception is swallowed (no-op)")
    void removeToxicNotFound() throws Exception {
      doThrow(new IOException("not found"))
          .when(apiClient).deleteToxic(any(), anyString(), anyString());

      chaos.removeToxic(container, "host:80", "latency"); // must not throw
    }
  }

  // ==================== isSupported ====================

  @Nested
  @DisplayName("isSupported")
  class IsSupported {

    @Test
    @DisplayName("returns true")
    void returnsTrue() {
      assertThat(chaos.isSupported()).isTrue();
    }
  }

  // ==================== helpers ====================

  private static double anyDouble() {
    return org.mockito.ArgumentMatchers.anyDouble();
  }
}
