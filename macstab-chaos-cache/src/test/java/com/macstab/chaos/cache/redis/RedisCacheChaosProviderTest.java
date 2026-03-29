/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * <p>All Toxiproxy interactions mocked via {@link ProxyChaos}.
 * Redis CLI operations use {@link GenericContainer#execInContainer} stubbed per test.
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
    lenient().when(container.isRunning()).thenReturn(true);
  }

  // ==================== TCP-Level Faults ====================

  @Nested
  @DisplayName("slowResponse")
  class SlowResponseTests {

    @Test
    @DisplayName("should delegate to proxy.addLatency")
    void shouldDelegateToProxy() {
      final Duration delay = Duration.ofMillis(200);
      chaos.slowResponse(container, delay);
      verify(proxy).createProxy(container, config.proxyName(), config.redisPort(), config.proxyPort());
      verify(proxy).addLatency(container, config.proxyName(), delay);
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.slowResponse(null, Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject null delay")
    void shouldRejectNullDelay() {
      assertThatThrownBy(() -> chaos.slowResponse(container, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject negative delay")
    void shouldRejectNegativeDelay() {
      assertThatThrownBy(() -> chaos.slowResponse(container, Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("should accept zero delay")
    void shouldAcceptZeroDelay() {
      chaos.slowResponse(container, Duration.ZERO);
      verify(proxy).addLatency(container, config.proxyName(), Duration.ZERO);
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
    @DisplayName("should delegate to proxy.addTimeout")
    void shouldDelegateToProxy() {
      chaos.injectConnectionFailures(container, 0.3);
      verify(proxy).createProxy(container, config.proxyName(), config.redisPort(), config.proxyPort());
      verify(proxy).addTimeout(eq(container), eq(config.proxyName()), any(Duration.class), eq(0.3));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, -1.0, 2.0})
    @DisplayName("should reject invalid rates")
    void shouldRejectInvalidRates(final double rate) {
      assertThatThrownBy(() -> chaos.injectConnectionFailures(container, rate))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("[0.0, 1.0]");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.3, 0.5, 1.0})
    @DisplayName("should accept valid rates")
    void shouldAcceptValidRates(final double rate) {
      chaos.injectConnectionFailures(container, rate);
      verify(proxy).addTimeout(eq(container), eq(config.proxyName()), any(Duration.class), eq(rate));
    }
  }

  @Nested
  @DisplayName("limitThroughput")
  class LimitThroughputTests {

    @Test
    @DisplayName("should delegate to proxy.limitBandwidth")
    void shouldDelegateToProxy() {
      chaos.limitThroughput(container, 10L);
      verify(proxy).createProxy(container, config.proxyName(), config.redisPort(), config.proxyPort());
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
  }

  @Nested
  @DisplayName("truncateResponses")
  class TruncateResponsesTests {

    @Test
    @DisplayName("should delegate to proxy.addLimitData")
    void shouldDelegateToProxy() {
      chaos.truncateResponses(container, 1024L);
      verify(proxy).createProxy(container, config.proxyName(), config.redisPort(), config.proxyPort());
      verify(proxy).addLimitData(container, config.proxyName(), 1024L);
    }

    @Test
    @DisplayName("should accept zero bytes (instant close)")
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
  }

  @Nested
  @DisplayName("removeFault / removeAllFaults")
  class RemoveFaultTests {

    @Test
    @DisplayName("removeFault should delegate to proxy.removeToxic")
    void removeFaultShouldDelegate() {
      chaos.removeFault(container, "latency");
      verify(proxy).removeToxic(container, config.proxyName(), "latency");
    }

    @Test
    @DisplayName("removeAllFaults should delegate to proxy.removeAllToxics")
    void removeAllFaultsShouldDelegate() {
      chaos.removeAllFaults(container);
      verify(proxy).removeAllToxics(container, config.proxyName());
    }

    @Test
    @DisplayName("removeFault should reject null faultName")
    void shouldRejectNullFaultName() {
      assertThatThrownBy(() -> chaos.removeFault(container, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("removeFault should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.removeFault(container, "latency"))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ==================== Data-Level Faults ====================

  @Nested
  @DisplayName("forceEviction")
  class ForceEvictionTests {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, 200})
    @DisplayName("should reject invalid percentage")
    void shouldRejectInvalidPercentage(final int pct) {
      assertThatThrownBy(() -> chaos.forceEviction(container, pct))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("[1, 100]");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 25, 50, 75, 100})
    @DisplayName("should accept valid percentages")
    void shouldAcceptValidPercentages(final int pct) throws Exception {
      stubExecSuccess();
      chaos.forceEviction(container, pct);
    }

    @Test
    @DisplayName("should throw ChaosOperationFailedException on exec failure")
    void shouldThrowOnExecFailure() throws Exception {
      stubExecFailure("redis-cli: connection refused");
      assertThatThrownBy(() -> chaos.forceEviction(container, 50))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("force eviction");
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.forceEviction(container, 50))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("limitMemory")
  class LimitMemoryTests {

    @Test
    @DisplayName("should reject negative bytes")
    void shouldRejectNegativeBytes() {
      assertThatThrownBy(() -> chaos.limitMemory(container, -1L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(">= 0");
    }

    @Test
    @DisplayName("should accept zero (remove limit)")
    void shouldAcceptZero() throws Exception {
      stubExecSuccess();
      chaos.limitMemory(container, 0L);
    }

    @Test
    @DisplayName("should accept positive bytes")
    void shouldAcceptPositiveBytes() throws Exception {
      stubExecSuccess();
      chaos.limitMemory(container, 64 * 1024 * 1024L);
    }

    @Test
    @DisplayName("should throw ChaosOperationFailedException on exec failure")
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
    @DisplayName("should reject null policy")
    void shouldRejectNull() {
      assertThatThrownBy(() -> chaos.setEvictionPolicy(container, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject blank policy")
    void shouldRejectBlank() {
      assertThatThrownBy(() -> chaos.setEvictionPolicy(container, "  "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("should accept valid policy")
    void shouldAcceptValidPolicy() throws Exception {
      stubExecSuccess();
      chaos.setEvictionPolicy(container, "allkeys-lru");
    }

    @Test
    @DisplayName("should throw ChaosOperationFailedException on exec failure")
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
    @DisplayName("should exec disconnect command")
    void shouldExecCommand() throws Exception {
      stubExecSuccess();
      chaos.disconnectClients(container);
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.disconnectClients(container))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("flushAll")
  class FlushAllTests {

    @Test
    @DisplayName("should exec FLUSHALL command")
    void shouldExecCommand() throws Exception {
      stubExecSuccess();
      chaos.flushAll(container);
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> chaos.flushAll(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should throw ChaosOperationFailedException on exec failure")
    void shouldThrowOnExecFailure() throws Exception {
      stubExecFailure("NOPERM this user has no permissions");
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
    @DisplayName("should delegate to proxy.deleteProxy (surgical, not nuclear)")
    void shouldCallDeleteProxy() {
      chaos.reset(container);
      verify(proxy).deleteProxy(container, config.proxyName());
      verify(proxy, never()).reset(any());
    }

    @Test
    @DisplayName("should no-op when container stopped")
    void shouldNoOpWhenStopped() {
      when(container.isRunning()).thenReturn(false);
      chaos.reset(container);
      verifyNoMoreInteractions(proxy);
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNull() {
      assertThatThrownBy(() -> chaos.reset(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("isSupported / installTools")
  class SupportTests {

    @Test
    @DisplayName("isSupported should return true")
    void isSupportedShouldBeTrue() {
      assertThat(chaos.isSupported()).isTrue();
    }

    @Test
    @DisplayName("installTools should be no-op (tools installed on demand)")
    void installToolsShouldBeNoOp() {
      chaos.installTools(container);
      verifyNoMoreInteractions(proxy);
    }
  }

  @Nested
  @DisplayName("deprecated injectMisses")
  class DeprecatedInjectMissesTests {

    @Test
    @DisplayName("should delegate to injectConnectionFailures")
    @SuppressWarnings("deprecation")
    void shouldDelegate() {
      chaos.injectMisses(container, "user:*", 0.3);
      verify(proxy).addTimeout(eq(container), eq(config.proxyName()), any(Duration.class), eq(0.3));
    }
  }

  // ==================== RedisChaosConfig ====================

  @Nested
  @DisplayName("RedisChaosConfig")
  class ConfigTests {

    @Test
    @DisplayName("defaults should have correct values")
    void defaultsShouldBeCorrect() {
      final RedisChaosConfig defaults = RedisChaosConfig.defaults();
      assertThat(defaults.redisPort()).isEqualTo(6379);
      assertThat(defaults.proxyPort()).isEqualTo(16379);
      assertThat(defaults.proxyName()).isEqualTo("redis_cache");
    }

    @Test
    @DisplayName("builder should override values")
    void builderShouldOverride() {
      final RedisChaosConfig custom = RedisChaosConfig.builder()
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
  }

  // ==================== Test Helpers ====================

  private void stubExecSuccess() throws Exception {
    org.mockito.Mockito.doReturn(TestExecResults.success())
        .when(container).execInContainer(anyString(), anyString(), anyString());
  }

  private void stubExecFailure(final String stderr) throws Exception {
    org.mockito.Mockito.doReturn(TestExecResults.failure(stderr))
        .when(container).execInContainer(anyString(), anyString(), anyString());
  }
}
