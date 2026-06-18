/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.cache.redis.config.RedisChaosConfig;
import com.macstab.chaos.cache.redis.support.TestExecResults;
import com.macstab.chaos.core.api.ProxyChaos;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * Unit tests for {@link RedisCacheChaosProvider}.
 *
 * <h2>Test Strategy</h2>
 *
 * <p><strong>TCP-level faults</strong> ({@code slowResponse}, {@code injectConnectionFailures},
 * {@code limitThroughput}, {@code truncateResponses}, {@code removeFault}, {@code
 * removeAllFaults}): verify delegation to {@link ProxyChaos} mock. No real containers.
 *
 * <p><strong>Data-level faults</strong> ({@code forceEviction}, {@code limitMemory}, {@code
 * setEvictionPolicy}, {@code disconnectClients}, {@code flushAll}): stub {@link
 * GenericContainer#execInContainer} via {@link TestExecResults}; verify happy path, failure path
 * ({@link ChaosOperationFailedException}), and guard conditions.
 *
 * <p><strong>Every public method</strong> is covered for:
 *
 * <ul>
 *   <li>Null container → {@link NullPointerException}
 *   <li>Stopped container → {@link IllegalStateException}
 *   <li>Invalid arguments → {@link IllegalArgumentException}
 *   <li>Happy path delegation / exec
 *   <li>Exec failure → {@link ChaosOperationFailedException} (data-level ops only)
 * </ul>
 *
 * <p>{@link RedisChaosConfig} validation is tested inline in {@link ConfigTests}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@ExtendWith(MockitoExtension.class)
class RedisCacheChaosProviderTest {

  @Mock private ProxyChaos proxy;
  @Mock private GenericContainer<?> container;

  private RedisChaosConfig config;
  private RedisCacheChaosProvider chaos;

  @BeforeEach
  void setUp() {
    config = RedisChaosConfig.defaults();
    chaos = new RedisCacheChaosProvider(config, proxy);
    // lenient: not all tests call isRunning (config tests, NPE tests)
    lenient().when(container.isRunning()).thenReturn(true);
  }

  // ==================== TCP-Level Faults ====================

  @Nested
  @DisplayName("slowResponse")
  class SlowResponseTests {

    @Test
    @DisplayName("should ensure proxy then delegate to proxy.addLatency")
    void shouldEnsureProxyAndDelegate() {
      final Duration delay = Duration.ofMillis(200);
      chaos.slowResponse(container, delay);
      verify(proxy)
          .createProxy(container, config.proxyName(), config.redisPort(), config.proxyPort());
      verify(proxy).addLatency(container, config.proxyName(), delay);
    }

    @Test
    @DisplayName("should accept zero delay (no latency)")
    void shouldAcceptZeroDelay() {
      chaos.slowResponse(container, Duration.ZERO);
      verify(proxy).addLatency(container, config.proxyName(), Duration.ZERO);
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.slowResponse(null, Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject null delay")
    void shouldRejectNullDelay() {
      assertThatThrownBy(() -> chaos.slowResponse(container, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("delay");
    }

    @Test
    @DisplayName("should reject negative delay")
    void shouldRejectNegativeDelay() {
      assertThatThrownBy(() -> chaos.slowResponse(container, Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.slowResponse(container, Duration.ofMillis(100)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be running");
    }
  }

  @Nested
  @DisplayName("injectConnectionFailures")
  class InjectConnectionFailuresTests {

    @Test
    @DisplayName("should ensure proxy then delegate to proxy.addTimeout")
    void shouldEnsureProxyAndDelegate() {
      chaos.injectConnectionFailures(container, 0.3);
      verify(proxy)
          .createProxy(container, config.proxyName(), config.redisPort(), config.proxyPort());
      verify(proxy).addTimeout(eq(container), eq(config.proxyName()), any(Duration.class), eq(0.3));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, -1.0, 2.0})
    @DisplayName("should reject out-of-range rates")
    void shouldRejectInvalidRates(final double rate) {
      assertThatThrownBy(() -> chaos.injectConnectionFailures(container, rate))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("[0.0, 1.0]");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.3, 0.5, 1.0})
    @DisplayName("should accept boundary and typical rates")
    void shouldAcceptValidRates(final double rate) {
      chaos.injectConnectionFailures(container, rate);
      verify(proxy)
          .addTimeout(eq(container), eq(config.proxyName()), any(Duration.class), eq(rate));
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.injectConnectionFailures(null, 0.3))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.injectConnectionFailures(container, 0.3))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("limitThroughput")
  class LimitThroughputTests {

    @Test
    @DisplayName("should ensure proxy then delegate to proxy.limitBandwidth")
    void shouldEnsureProxyAndDelegate() {
      chaos.limitThroughput(container, 10L);
      verify(proxy)
          .createProxy(container, config.proxyName(), config.redisPort(), config.proxyPort());
      verify(proxy).limitBandwidth(container, config.proxyName(), 10L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    @DisplayName("should reject non-positive rates")
    void shouldRejectNonPositiveRates(final long rateKBps) {
      assertThatThrownBy(() -> chaos.limitThroughput(container, rateKBps))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.limitThroughput(null, 10L))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.limitThroughput(container, 10L))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("truncateResponses")
  class TruncateResponsesTests {

    @Test
    @DisplayName("should ensure proxy then delegate to proxy.addLimitData")
    void shouldEnsureProxyAndDelegate() {
      chaos.truncateResponses(container, 1024L);
      verify(proxy)
          .createProxy(container, config.proxyName(), config.redisPort(), config.proxyPort());
      verify(proxy).addLimitData(container, config.proxyName(), 1024L);
    }

    @Test
    @DisplayName("should accept zero bytes (instant close on first data)")
    void shouldAcceptZeroBytes() {
      chaos.truncateResponses(container, 0L);
      verify(proxy).addLimitData(container, config.proxyName(), 0L);
    }

    @Test
    @DisplayName("should reject negative bytes")
    void shouldRejectNegativeBytes() {
      assertThatThrownBy(() -> chaos.truncateResponses(container, -1L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(">= 0");
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.truncateResponses(null, 100L))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.truncateResponses(container, 100L))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("removeFault")
  class RemoveFaultTests {

    @Test
    @DisplayName("should delegate to proxy.removeToxic")
    void shouldDelegate() {
      chaos.removeFault(container, "latency");
      verify(proxy).removeToxic(container, config.proxyName(), "latency");
    }

    @Test
    @DisplayName("should reject null faultName")
    void shouldRejectNullFaultName() {
      assertThatThrownBy(() -> chaos.removeFault(container, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("faultName");
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.removeFault(null, "latency"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.removeFault(container, "latency"))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("removeAllFaults")
  class RemoveAllFaultsTests {

    @Test
    @DisplayName("should delegate to proxy.removeAllToxics")
    void shouldDelegate() {
      chaos.removeAllFaults(container);
      verify(proxy).removeAllToxics(container, config.proxyName());
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.removeAllFaults(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.removeAllFaults(container))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ==================== Data-Level Faults ====================

  @Nested
  @DisplayName("forceEviction")
  class ForceEvictionTests {

    @ParameterizedTest
    @ValueSource(ints = {1, 25, 50, 75, 100})
    @DisplayName("should exec eviction command for valid percentages")
    void shouldAcceptValidPercentages(final int pct) throws Exception {
      stubExecSuccess();
      chaos.forceEviction(container, pct);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, 200})
    @DisplayName("should reject invalid percentage range")
    void shouldRejectInvalidPercentage(final int pct) {
      assertThatThrownBy(() -> chaos.forceEviction(container, pct))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("[1, 100]");
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.forceEviction(null, 50))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.forceEviction(container, 50))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should throw ChaosOperationFailedException on non-zero exec exit")
    void shouldThrowOnExecFailure() throws Exception {
      stubExecFailure("redis-cli: connection refused");
      assertThatThrownBy(() -> chaos.forceEviction(container, 50))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("force eviction");
    }

    @Test
    @DisplayName("should wrap unexpected exec exception in ChaosOperationFailedException")
    void shouldWrapUnexpectedException() throws Exception {
      stubExecThrows(new RuntimeException("Docker daemon died"));
      assertThatThrownBy(() -> chaos.forceEviction(container, 50))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("force eviction");
    }
  }

  @Nested
  @DisplayName("limitMemory")
  class LimitMemoryTests {

    @Test
    @DisplayName("should accept zero (remove limit)")
    void shouldAcceptZero() throws Exception {
      stubExecSuccess();
      chaos.limitMemory(container, 0L);
    }

    @Test
    @DisplayName("should accept positive byte limit")
    void shouldAcceptPositiveBytes() throws Exception {
      stubExecSuccess();
      chaos.limitMemory(container, 64 * 1024 * 1024L);
    }

    @Test
    @DisplayName("should reject negative bytes")
    void shouldRejectNegativeBytes() {
      assertThatThrownBy(() -> chaos.limitMemory(container, -1L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(">= 0");
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.limitMemory(null, 1024L))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.limitMemory(container, 1024L))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should throw ChaosOperationFailedException on non-zero exec exit")
    void shouldThrowOnExecFailure() throws Exception {
      stubExecFailure("ERR: CONFIG not allowed");
      assertThatThrownBy(() -> chaos.limitMemory(container, 1024L))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("set memory limit");
    }
  }

  @Nested
  @DisplayName("setEvictionPolicy")
  class SetEvictionPolicyTests {

    @Test
    @DisplayName("should exec policy command for valid policy")
    void shouldAcceptValidPolicy() throws Exception {
      stubExecSuccess();
      chaos.setEvictionPolicy(container, "allkeys-lru");
    }

    @Test
    @DisplayName("should reject null policy")
    void shouldRejectNullPolicy() {
      assertThatThrownBy(() -> chaos.setEvictionPolicy(container, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("policy");
    }

    @Test
    @DisplayName("should reject blank policy")
    void shouldRejectBlankPolicy() {
      assertThatThrownBy(() -> chaos.setEvictionPolicy(container, "  "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.setEvictionPolicy(null, "allkeys-lru"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.setEvictionPolicy(container, "allkeys-lru"))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should throw ChaosOperationFailedException on non-zero exec exit")
    void shouldThrowOnExecFailure() throws Exception {
      stubExecFailure("ERR Unknown policy");
      assertThatThrownBy(() -> chaos.setEvictionPolicy(container, "bad-policy"))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("set eviction policy");
    }
  }

  @Nested
  @DisplayName("disconnectClients")
  class DisconnectClientsTests {

    @Test
    @DisplayName("should exec CLIENT KILL command")
    void shouldExecClientKillCommand() throws Exception {
      stubExecSuccess();
      chaos.disconnectClients(container);
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.disconnectClients(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.disconnectClients(container))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should throw ChaosOperationFailedException on non-zero exec exit")
    void shouldThrowOnExecFailure() throws Exception {
      stubExecFailure("NOPERM no permissions");
      assertThatThrownBy(() -> chaos.disconnectClients(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("disconnect clients");
    }
  }

  @Nested
  @DisplayName("flushAll")
  class FlushAllTests {

    @Test
    @DisplayName("should exec FLUSHALL command")
    void shouldExecFlushAllCommand() throws Exception {
      stubExecSuccess();
      chaos.flushAll(container);
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.flushAll(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.flushAll(container)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should throw ChaosOperationFailedException on non-zero exec exit")
    void shouldThrowOnExecFailure() throws Exception {
      stubExecFailure("NOPERM no permissions for FLUSHALL");
      assertThatThrownBy(() -> chaos.flushAll(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("flush all");
    }
  }

  // ==================== Lifecycle ====================

  @Nested
  @DisplayName("reset")
  class ResetTests {

    @Test
    @DisplayName("should call proxy.deleteProxy — surgical, not nuclear")
    void shouldCallDeleteProxy() {
      chaos.reset(container);
      verify(proxy).deleteProxy(container, config.proxyName());
      verify(proxy, never()).reset(any());
    }

    @Test
    @DisplayName("should no-op silently when container is stopped")
    void shouldNoOpWhenStopped() {
      when(container.isRunning()).thenReturn(false);
      chaos.reset(container);
      verifyNoMoreInteractions(proxy);
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.reset(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }
  }

  @Nested
  @DisplayName("isSupported")
  class IsSupportedTests {

    @Test
    @DisplayName("should always return true (Redis implementation is supported)")
    void shouldReturnTrue() {
      assertThat(chaos.isSupported()).isTrue();
    }
  }

  @Nested
  @DisplayName("installTools")
  class InstallToolsTests {

    @Test
    @DisplayName("should be a no-op — tools installed lazily by proxy on first createProxy call")
    void shouldBeNoOp() {
      chaos.installTools(container);
      verifyNoMoreInteractions(proxy);
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.installTools(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }
  }

  @Nested
  @DisplayName("injectMisses (deprecated)")
  class DeprecatedInjectMissesTests {

    @Test
    @DisplayName("should delegate to injectConnectionFailures — TCP drop, not RESP miss")
    @SuppressWarnings("deprecation")
    void shouldDelegateToInjectConnectionFailures() {
      chaos.injectMisses(container, "user:*", 0.3);
      verify(proxy).addTimeout(eq(container), eq(config.proxyName()), any(Duration.class), eq(0.3));
    }
  }

  // ==================== RedisChaosConfig ====================

  @Nested
  @DisplayName("RedisChaosConfig")
  class ConfigTests {

    @Test
    @DisplayName("defaults should match documented values")
    void defaultsShouldMatchDocumentedValues() {
      final RedisChaosConfig defaults = RedisChaosConfig.defaults();
      assertThat(defaults.redisPort()).isEqualTo(6379);
      assertThat(defaults.proxyPort()).isEqualTo(16379);
      assertThat(defaults.proxyName()).isEqualTo("redis_cache");
    }

    @Test
    @DisplayName("builder should fully override all values")
    void builderShouldOverrideAllValues() {
      final RedisChaosConfig custom =
          RedisChaosConfig.builder()
              .redisPort(6380)
              .proxyPort(16380)
              .proxyName("redis_primary")
              .build();
      assertThat(custom.redisPort()).isEqualTo(6380);
      assertThat(custom.proxyPort()).isEqualTo(16380);
      assertThat(custom.proxyName()).isEqualTo("redis_primary");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, 100000})
    @DisplayName("should reject invalid redisPort")
    void shouldRejectInvalidRedisPort(final int port) {
      assertThatThrownBy(() -> RedisChaosConfig.builder().redisPort(port).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536})
    @DisplayName("should reject invalid proxyPort")
    void shouldRejectInvalidProxyPort(final int port) {
      assertThatThrownBy(() -> RedisChaosConfig.builder().proxyPort(port).build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject equal redisPort and proxyPort")
    void shouldRejectEqualPorts() {
      assertThatThrownBy(() -> RedisChaosConfig.builder().redisPort(6379).proxyPort(6379).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("should reject blank proxyName")
    void shouldRejectBlankProxyName() {
      assertThatThrownBy(() -> RedisChaosConfig.builder().proxyName("  ").build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("should reject null proxyName")
    void shouldRejectNullProxyName() {
      assertThatThrownBy(() -> RedisChaosConfig.builder().proxyName(null).build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== Test Helpers ====================

  private void stubExecSuccess() throws Exception {
    doReturn(TestExecResults.success())
        .when(container)
        .execInContainer(anyString(), anyString(), anyString());
  }

  private void stubExecFailure(final String stderr) throws Exception {
    doReturn(TestExecResults.failure(stderr))
        .when(container)
        .execInContainer(anyString(), anyString(), anyString());
  }

  private void stubExecThrows(final Exception ex) throws Exception {
    doThrow(ex).when(container).execInContainer(anyString(), anyString(), anyString());
  }
}
