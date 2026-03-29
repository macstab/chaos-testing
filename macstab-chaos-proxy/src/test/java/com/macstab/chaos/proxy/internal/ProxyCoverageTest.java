/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.proxy.ProxyChaosProvider;
import com.macstab.chaos.proxy.api.ToxiproxyApiClient;
import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycleManager;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;
import com.macstab.chaos.proxy.internal.operations.ProxyOperationsManager;
import com.macstab.chaos.proxy.internal.operations.ToxicOperationsManager;
import com.macstab.chaos.proxy.internal.operations.toxic.*;
import com.macstab.chaos.proxy.internal.toxiproxy.ToxiproxyInstaller;
import com.macstab.chaos.proxy.network.NetworkRedirectManager;

/**
 * Targeted coverage tests for proxy module uncovered paths.
 *
 * <p>Uses Mockito to inject API clients and test error paths, injectable constructors,
 * validation, and edge cases without requiring live Toxiproxy.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("Proxy Module - Coverage")
@Testcontainers
class ProxyCoverageTest {

  /**
   * Shared running container for tests that need ContainerContext resolution.
   * Alpine with NET_ADMIN so iptables and platform detection work.
   */
  @Container
  @SuppressWarnings("resource")
  private static final GenericContainer<?> SHARED_REDIS =
      new GenericContainer<>("redis:7.4")
          .withExposedPorts(6379)
          .withCreateContainerCmdModifier(cmd ->
              cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));

  private final ToxiproxyConfig config = ToxiproxyConfig.defaults();

  // ──────────────────────────────────────────────────────────────────────────
  // Injectable constructors (2-arg / 3-arg)
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Injectable constructors")
  class InjectableConstructorsTest {

    @Test
    @DisplayName("ToxicOperationsManager(config, apiClient) stores both deps")
    void toxicOpsManager_injectableConstructor() {
      ToxiproxyApiClient apiClient = mock(ToxiproxyApiClient.class);
      ToxicOperationsManager mgr = new ToxicOperationsManager(config, apiClient);
      assertThat(mgr).isNotNull();
    }

    @Test
    @DisplayName("ToxicOperationsManager null apiClient throws NPE")
    void toxicOpsManager_nullApiClient_throws() {
      assertThatThrownBy(() -> new ToxicOperationsManager(config, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("apiClient");
    }

    @Test
    @DisplayName("ProxyOperationsManager(config, apiClient, networkRedirect) stores all deps")
    void proxyOpsManager_injectableConstructor() {
      ToxiproxyApiClient apiClient = mock(ToxiproxyApiClient.class);
      NetworkRedirectManager redirect = mock(NetworkRedirectManager.class);
      ProxyOperationsManager mgr = new ProxyOperationsManager(config, apiClient, redirect);
      assertThat(mgr).isNotNull();
    }

    @Test
    @DisplayName("ProxyOperationsManager null apiClient throws NPE")
    void proxyOpsManager_nullApiClient_throws() {
      NetworkRedirectManager redirect = mock(NetworkRedirectManager.class);
      assertThatThrownBy(() -> new ProxyOperationsManager(config, null, redirect))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("apiClient");
    }

    @Test
    @DisplayName("ProxyOperationsManager null networkRedirect throws NPE")
    void proxyOpsManager_nullRedirect_throws() {
      ToxiproxyApiClient apiClient = mock(ToxiproxyApiClient.class);
      assertThatThrownBy(() -> new ProxyOperationsManager(config, apiClient, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("networkRedirect");
    }

    @Test
    @DisplayName("ToxiproxyLifecycleManager(config, installer, apiClient) stores all deps")
    void lifecycleManager_injectableConstructor() {
      ToxiproxyInstaller installer = mock(ToxiproxyInstaller.class);
      ToxiproxyApiClient apiClient = mock(ToxiproxyApiClient.class);
      ToxiproxyLifecycleManager mgr = new ToxiproxyLifecycleManager(config, installer, apiClient);
      assertThat(mgr).isNotNull();
    }

    @Test
    @DisplayName("ToxiproxyLifecycleManager null installer throws NPE")
    void lifecycleManager_nullInstaller_throws() {
      ToxiproxyApiClient apiClient = mock(ToxiproxyApiClient.class);
      assertThatThrownBy(() -> new ToxiproxyLifecycleManager(config, null, apiClient))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("installer");
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // ToxicOperationsManager.toxicExists — unit via injectable constructor
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @Testcontainers
  @DisplayName("ToxicOperationsManager.toxicExists")
  class ToxicExistsTest {

    @Container
    @SuppressWarnings("resource")
    private final GenericContainer<?> CONTAINER =
        new GenericContainer<>("redis:7.4")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd ->
                cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));

    @Test
    @DisplayName("returns false when container not running")
    void notRunning_returnsFalse() {
      // GIVEN — mock container that reports isRunning() = false
      final GenericContainer<?> stopped = mock(GenericContainer.class);
      when(stopped.isRunning()).thenReturn(false);
      final com.macstab.chaos.core.platform.Platform platform =
          mock(com.macstab.chaos.core.platform.Platform.class);
      final com.macstab.chaos.core.shell.Shell shell =
          mock(com.macstab.chaos.core.shell.Shell.class);
      final ContainerContext stoppedCtx = ContainerContext.of(stopped, platform, shell);
      final ToxicOperationsManager mgr = new ToxicOperationsManager(config);

      assertThat(mgr.toxicExists(stoppedCtx, "redis", "latency")).isFalse();
    }

    @Test
    @DisplayName("returns false on API exception")
    void apiException_returnsFalse() throws Exception {
      ToxiproxyApiClient apiClient = mock(ToxiproxyApiClient.class);
      when(apiClient.toxicExists(any(), any(), any()))
          .thenThrow(new IOException("network error"));
      ToxicOperationsManager mgr = new ToxicOperationsManager(config, apiClient);
      assertThat(mgr.toxicExists(ContainerContext.of(CONTAINER), "redis", "latency")).isFalse();
    }

    @Test
    @DisplayName("null container throws NPE")
    void nullContainer_throws() {
      ToxicOperationsManager mgr = new ToxicOperationsManager(config);
      assertThatThrownBy(() -> mgr.toxicExists(null, "redis", "latency"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null proxyName throws NPE")
    void nullProxyName_throws() {
      ToxicOperationsManager mgr = new ToxicOperationsManager(config);
      assertThatThrownBy(() -> mgr.toxicExists(ContainerContext.of(CONTAINER), null, "latency"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null toxicName throws NPE")
    void nullToxicName_throws() {
      ToxicOperationsManager mgr = new ToxicOperationsManager(config);
      assertThatThrownBy(() -> mgr.toxicExists(ContainerContext.of(CONTAINER), "redis", null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // ToxiproxyOrchestrator — validateToxicity + error handler paths
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("ToxiproxyOrchestrator - validation and error paths")
  class OrchestratorValidationTest {

    @Test
    @DisplayName("addToxic with null toxicConfig throws NPE")
    void nullToxicConfig_throws() {
      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);
      @SuppressWarnings("resource")
      final GenericContainer<?> container = new GenericContainer<>("alpine:latest");
      assertThatThrownBy(() -> orchestrator.addToxic(container, "redis", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("toxicConfig");
    }

    @Test
    @DisplayName("addToxic with null proxyName throws NPE")
    void nullProxyName_throws() {
      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);
      final LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();
      @SuppressWarnings("resource")
      final GenericContainer<?> container = new GenericContainer<>("alpine:latest");
      assertThatThrownBy(() -> orchestrator.addToxic(container, null, toxic))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyName");
    }

    @Test
    @DisplayName("reset on null container throws NPE")
    void reset_nullContainer_throws() {
      ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);
      assertThatThrownBy(() -> orchestrator.reset(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("removeToxic: null container throws NPE")
    void removeToxic_nullContainer_throws() {
      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);
      assertThatThrownBy(() -> orchestrator.removeToxic(null, "redis", "latency"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("removeToxic: null proxyName throws NPE")
    void removeToxic_nullProxyName_throws() {
      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);
      @SuppressWarnings("resource")
      final GenericContainer<?> container = new GenericContainer<>("alpine:latest");
      assertThatThrownBy(() -> orchestrator.removeToxic(container, null, "latency"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyName");
    }

    @Test
    @DisplayName("removeToxic: null toxicName throws NPE")
    void removeToxic_nullToxicName_throws() {
      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);
      @SuppressWarnings("resource")
      final GenericContainer<?> container = new GenericContainer<>("alpine:latest");
      assertThatThrownBy(() -> orchestrator.removeToxic(container, "redis", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("toxicName");
    }

    @Test
    @DisplayName("removeAllToxics: null container throws NPE")
    void removeAllToxics_nullContainer_throws() {
      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);
      assertThatThrownBy(() -> orchestrator.removeAllToxics(null, "redis"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("removeAllToxics: null proxyName throws NPE")
    void removeAllToxics_nullProxyName_throws() {
      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);
      @SuppressWarnings("resource")
      final GenericContainer<?> container = new GenericContainer<>("alpine:latest");
      assertThatThrownBy(() -> orchestrator.removeAllToxics(container, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyName");
    }

    @Test
    @DisplayName("4-arg constructor: null lifecycle throws NPE")
    void constructor_nullLifecycle_throws() {
      assertThatThrownBy(() -> new ToxiproxyOrchestrator(null,
          mock(com.macstab.chaos.proxy.internal.operations.ProxyOperations.class),
          mock(com.macstab.chaos.proxy.internal.operations.ToxicOperations.class),
          mock(com.macstab.chaos.proxy.network.NetworkRedirect.class)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("lifecycle");
    }

    @Test
    @DisplayName("4-arg constructor: null proxyOps throws NPE")
    void constructor_nullProxyOps_throws() {
      assertThatThrownBy(() -> new ToxiproxyOrchestrator(
          mock(com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle.class),
          null,
          mock(com.macstab.chaos.proxy.internal.operations.ToxicOperations.class),
          mock(com.macstab.chaos.proxy.network.NetworkRedirect.class)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyOps");
    }

    @Test
    @DisplayName("4-arg constructor: null toxicOps throws NPE")
    void constructor_nullToxicOps_throws() {
      assertThatThrownBy(() -> new ToxiproxyOrchestrator(
          mock(com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle.class),
          mock(com.macstab.chaos.proxy.internal.operations.ProxyOperations.class),
          null,
          mock(com.macstab.chaos.proxy.network.NetworkRedirect.class)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("toxicOps");
    }

    @Test
    @DisplayName("4-arg constructor: null networkRedirect throws NPE")
    void constructor_nullNetworkRedirect_throws() {
      assertThatThrownBy(() -> new ToxiproxyOrchestrator(
          mock(com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle.class),
          mock(com.macstab.chaos.proxy.internal.operations.ProxyOperations.class),
          mock(com.macstab.chaos.proxy.internal.operations.ToxicOperations.class),
          null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("networkRedirect");
    }

    @Test
    @DisplayName("4-arg constructor: valid args constructs successfully")
    void constructor_validArgs_succeeds() {
      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(
          mock(com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle.class),
          mock(com.macstab.chaos.proxy.internal.operations.ProxyOperations.class),
          mock(com.macstab.chaos.proxy.internal.operations.ToxicOperations.class),
          mock(com.macstab.chaos.proxy.network.NetworkRedirect.class));
      assertThat(orchestrator).isNotNull();
    }

    @Test
    @DisplayName("removeToxic re-throws ChaosOperationFailedException directly")
    void removeToxic_chaosException_rethrown() throws Exception {
      // GIVEN — mocked toxicOps that throws ChaosOperationFailedException
      final com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle lifecycle =
          mock(com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle.class);
      final com.macstab.chaos.proxy.internal.operations.ToxicOperations toxicOps =
          mock(com.macstab.chaos.proxy.internal.operations.ToxicOperations.class);
      final com.macstab.chaos.core.exception.ChaosOperationFailedException expected =
          new com.macstab.chaos.core.exception.ChaosOperationFailedException("toxic error");
      doThrow(expected).when(toxicOps).removeToxic(any(), any(), any());

      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(
          lifecycle,
          mock(com.macstab.chaos.proxy.internal.operations.ProxyOperations.class),
          toxicOps,
          mock(com.macstab.chaos.proxy.network.NetworkRedirect.class));

      // WHEN / THEN — same instance re-thrown, not wrapped
      assertThatThrownBy(() -> orchestrator.removeToxic(SHARED_REDIS, "redis", "latency"))
          .isSameAs(expected);
    }

    @Test
    @DisplayName("removeAllToxics re-throws ChaosOperationFailedException directly")
    void removeAllToxics_chaosException_rethrown() throws Exception {
      // GIVEN
      final com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle lifecycle =
          mock(com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle.class);
      final com.macstab.chaos.proxy.internal.operations.ToxicOperations toxicOps =
          mock(com.macstab.chaos.proxy.internal.operations.ToxicOperations.class);
      final com.macstab.chaos.core.exception.ChaosOperationFailedException expected =
          new com.macstab.chaos.core.exception.ChaosOperationFailedException("remove all error");
      doThrow(expected).when(toxicOps).removeAllToxics(any(), any());

      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(
          lifecycle,
          mock(com.macstab.chaos.proxy.internal.operations.ProxyOperations.class),
          toxicOps,
          mock(com.macstab.chaos.proxy.network.NetworkRedirect.class));

      // WHEN / THEN
      assertThatThrownBy(() -> orchestrator.removeAllToxics(SHARED_REDIS, "redis"))
          .isSameAs(expected);
    }

    @Test
    @DisplayName("reset logs warning when exception thrown — does not rethrow")
    void reset_exception_logsAndSwallows() throws Exception {
      // GIVEN — networkRedirect throws during clearAllRedirects
      final com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle lifecycle =
          mock(com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle.class);
      final com.macstab.chaos.proxy.network.NetworkRedirect networkRedirect =
          mock(com.macstab.chaos.proxy.network.NetworkRedirect.class);
      doThrow(new RuntimeException("iptables error")).when(networkRedirect).clearAllRedirects(any());

      final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(
          lifecycle,
          mock(com.macstab.chaos.proxy.internal.operations.ProxyOperations.class),
          mock(com.macstab.chaos.proxy.internal.operations.ToxicOperations.class),
          networkRedirect);

      // WHEN / THEN — reset must NOT throw; exception is swallowed with a warning
      assertThatNoException().isThrownBy(() -> orchestrator.reset(SHARED_REDIS));
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Toxic validation — negative/boundary values
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Toxic validation - boundary values")
  class ToxicValidationTest {

    @Test
    @DisplayName("BandwidthToxic: rateKbps <= 0 throws")
    void bandwidth_zeroRate_throws() {
      assertThatThrownBy(() -> BandwidthToxic.builder().name("b").rateKbps(0).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rateKbps");
    }

    @Test
    @DisplayName("BandwidthToxic: negative rate throws")
    void bandwidth_negativeRate_throws() {
      assertThatThrownBy(() -> BandwidthToxic.builder().name("b").rateKbps(-1).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BandwidthToxic: toxicity > 1.0 throws")
    void bandwidth_toxicityAboveOne_throws() {
      assertThatThrownBy(() -> BandwidthToxic.builder().name("b").rateKbps(100).toxicity(1.5).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("BandwidthToxic: toxicity < 0 throws")
    void bandwidth_toxicityBelowZero_throws() {
      assertThatThrownBy(() -> BandwidthToxic.builder().name("b").rateKbps(100).toxicity(-0.1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("SlowCloseToxic: delayMs < 0 throws")
    void slowClose_negativeDelay_throws() {
      assertThatThrownBy(() -> SlowCloseToxic.builder().name("s").delayMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("delayMs");
    }

    @Test
    @DisplayName("SlowCloseToxic: toxicity > 1.0 throws")
    void slowClose_toxicityAboveOne_throws() {
      assertThatThrownBy(() -> SlowCloseToxic.builder().name("s").delayMs(100).toxicity(1.5).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("SlowCloseToxic: Builder.toxicity() call (branch coverage)")
    void slowClose_builderToxicity() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("s").delayMs(100).toxicity(0.5).build();
      assertThat(toxic.toxicity()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("LatencyToxic: latencyMs < 0 throws")
    void latency_negativeLatency_throws() {
      assertThatThrownBy(() -> LatencyToxic.builder().name("l").latencyMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("latencyMs");
    }

    @Test
    @DisplayName("LatencyToxic: jitterMs < 0 throws")
    void latency_negativeJitter_throws() {
      assertThatThrownBy(() -> LatencyToxic.builder().name("l").latencyMs(100).jitterMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("jitterMs");
    }

    @Test
    @DisplayName("LatencyToxic: toxicity > 1.0 throws")
    void latency_toxicityAboveOne_throws() {
      assertThatThrownBy(() -> LatencyToxic.builder().name("l").latencyMs(100).toxicity(1.5).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("LimitDataToxic: negative bytes throws")
    void limitData_negativeBytes_throws() {
      assertThatThrownBy(() -> LimitDataToxic.builder().name("l").bytes(-1).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LimitDataToxic: zero bytes allowed (closes immediately)")
    void limitData_zeroBytes_allowed() {
      final LimitDataToxic toxic = LimitDataToxic.builder().name("l").bytes(0).build();
      assertThat(toxic.bytes()).isZero();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // ProxyChaosProvider — limitBandwidth, slowClose, validation
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("ProxyChaosProvider - validation paths")
  class ProxyChaosProviderValidationTest {

    private ProxyChaosProvider provider;

    @BeforeEach
    void setup() {
      provider = new ProxyChaosProvider(config);
    }

    @Test
    @DisplayName("limitBandwidth: rateKBps <= 0 throws")
    void limitBandwidth_zeroRate_throws() {
      @SuppressWarnings("resource")
      GenericContainer<?> container = new GenericContainer<>("alpine:latest");
      assertThatThrownBy(() -> provider.limitBandwidth(container, "redis", 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rateKBps");
    }

    @Test
    @DisplayName("limitBandwidth: null container throws NPE")
    void limitBandwidth_nullContainer_throws() {
      assertThatThrownBy(() -> provider.limitBandwidth(null, "redis", 100))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("limitBandwidth: null proxyName throws NPE")
    void limitBandwidth_nullProxyName_throws() {
      @SuppressWarnings("resource")
      GenericContainer<?> container = new GenericContainer<>("alpine:latest");
      assertThatThrownBy(() -> provider.limitBandwidth(container, null, 100))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("slowClose: negative delay throws")
    void slowClose_negativeDelay_throws() {
      @SuppressWarnings("resource")
      GenericContainer<?> container = new GenericContainer<>("alpine:latest");
      assertThatThrownBy(() -> provider.slowClose(container, "redis", Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("delay");
    }

    @Test
    @DisplayName("slowClose: null container throws NPE")
    void slowClose_nullContainer_throws() {
      assertThatThrownBy(() -> provider.slowClose(null, "redis", Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("slowClose: null delay throws NPE")
    void slowClose_nullDelay_throws() {
      @SuppressWarnings("resource")
      GenericContainer<?> container = new GenericContainer<>("alpine:latest");
      assertThatThrownBy(() -> provider.slowClose(container, "redis", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("isSupported returns true")
    void isSupported_returnsTrue() {
      assertThat(provider.isSupported()).isTrue();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // ToxiproxyInstaller — handleInstallationError
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @Testcontainers
  @DisplayName("ToxiproxyInstaller - error paths")
  class ToxiproxyInstallerErrorTest {

    @Container
    @SuppressWarnings("resource")
    private final GenericContainer<?> UBUNTU =
        new GenericContainer<>("ubuntu:22.04").withCommand("sleep", "infinity");

    @Test
    @DisplayName("install null ctx throws NPE")
    void nullCtx_throws() {
      final ToxiproxyInstaller installer = new ToxiproxyInstaller();
      assertThatThrownBy(() -> installer.install(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("ctx");
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // ToxiproxyLifecycleManager — stop error path, isHealthy false path
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @Testcontainers
  @DisplayName("ToxiproxyLifecycleManager - stop and isHealthy paths")
  class LifecycleManagerPathsTest {

    @Container
    @SuppressWarnings("resource")
    private final GenericContainer<?> UBUNTU =
        new GenericContainer<>("ubuntu:22.04").withCommand("sleep", "infinity");

    @Test
    @DisplayName("isHealthy returns false on stopped container")
    void isHealthy_stoppedContainer_returnsFalse() {
      final ToxiproxyLifecycleManager mgr = new ToxiproxyLifecycleManager(config);
      final GenericContainer<?> stopped = mock(GenericContainer.class);
      when(stopped.isRunning()).thenReturn(false);
      final com.macstab.chaos.core.platform.Platform platform =
          mock(com.macstab.chaos.core.platform.Platform.class);
      final com.macstab.chaos.core.shell.Shell shell =
          mock(com.macstab.chaos.core.shell.Shell.class);
      final ContainerContext stoppedCtx = ContainerContext.of(stopped, platform, shell);

      assertThat(mgr.isHealthy(stoppedCtx)).isFalse();
    }

    @Test
    @DisplayName("isHealthy returns false when apiClient.isApiReady throws RuntimeException")
    void isHealthy_apiThrows_returnsFalse() {
      ToxiproxyInstaller installer = mock(ToxiproxyInstaller.class);
      ToxiproxyApiClient apiClient = mock(ToxiproxyApiClient.class);
      when(apiClient.isApiReady(any())).thenThrow(new RuntimeException("connection refused"));

      ToxiproxyLifecycleManager mgr = new ToxiproxyLifecycleManager(config, installer, apiClient);
      assertThat(mgr.isHealthy(ContainerContext.of(UBUNTU))).isFalse();
    }

    @Test
    @DisplayName("stop on non-running container throws IllegalStateException")
    void stop_nonRunningContainer_throws() {
      // GIVEN — a ctx whose container reports isRunning() = false
      final ToxiproxyLifecycleManager mgr = new ToxiproxyLifecycleManager(config);
      final GenericContainer<?> stopped = mock(GenericContainer.class);
      when(stopped.isRunning()).thenReturn(false);
      final com.macstab.chaos.core.platform.Platform platform =
          mock(com.macstab.chaos.core.platform.Platform.class);
      final com.macstab.chaos.core.shell.Shell shell =
          mock(com.macstab.chaos.core.shell.Shell.class);
      final ContainerContext stoppedCtx = ContainerContext.of(stopped, platform, shell);

      // WHEN / THEN
      assertThatThrownBy(() -> mgr.stop(stoppedCtx))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be running");
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // NetworkRedirectManager — null ctx guard paths
  // ──────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("NetworkRedirectManager - null ctx validation")
  class NetworkRedirectManagerPathsTest {

    @Test
    @DisplayName("setupRedirect null ctx throws NPE")
    void setupRedirect_nullCtx_throws() {
      final NetworkRedirectManager mgr = new NetworkRedirectManager();
      assertThatNullPointerException()
          .isThrownBy(() -> mgr.setupRedirect(null, 6379, 16379))
          .withMessage("ctx must not be null");
    }

    @Test
    @DisplayName("removeRedirect null ctx throws NPE")
    void removeRedirect_nullCtx_throws() {
      final NetworkRedirectManager mgr = new NetworkRedirectManager();
      assertThatNullPointerException()
          .isThrownBy(() -> mgr.removeRedirect(null, 6379, 16379))
          .withMessage("ctx must not be null");
    }

    @Test
    @DisplayName("clearAllRedirects null ctx throws NPE")
    void clearAll_nullCtx_throws() {
      final NetworkRedirectManager mgr = new NetworkRedirectManager();
      assertThatNullPointerException()
          .isThrownBy(() -> mgr.clearAllRedirects(null))
          .withMessage("ctx must not be null");
    }
  }
}
