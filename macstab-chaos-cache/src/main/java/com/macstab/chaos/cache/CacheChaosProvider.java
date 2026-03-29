/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache;

import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.cache.config.CacheChaosConfig;
import com.macstab.chaos.cache.internal.RedisCommandBuilder;
import com.macstab.chaos.core.api.CacheChaos;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.util.Shell;
import com.macstab.chaos.proxy.ProxyChaosProvider;
import com.macstab.chaos.core.api.ProxyChaos;

import lombok.extern.slf4j.Slf4j;

/**
 * Cache chaos provider for Redis containers.
 *
 * <p>Delegates ALL Toxiproxy lifecycle management to {@link ProxyChaosProvider}. This class
 * contains only Redis-specific logic (port configuration, redis-cli operations, validation).
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>⚠️ CRITICAL: reset() is targeted, not nuclear</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p>{@link #reset} calls {@code proxy.deleteProxy(container, config.proxyName())} — it removes
 * only the Redis proxy and its iptables rule. The Toxiproxy process and all other proxies on the
 * container (e.g., a Postgres proxy from another module) stay alive. Safe for {@code @AfterEach}.
 *
 * <p>For full nuclear teardown (kill Toxiproxy + all iptables), use the underlying
 * {@link ProxyChaosProvider#reset(GenericContainer)} directly from your {@code @AfterAll}.
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Architecture</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>
 * CacheChaosProvider
 *     ├── ProxyChaosProvider   ← Toxiproxy install, lifecycle, iptables, toxic management
 *     └── RedisCommandBuilder  ← redis-cli commands (eviction, memory, clients, flush)
 * </pre>
 *
 * <p>TCP-level faults route through Toxiproxy ({@link ProxyChaos}).
 * Redis-level faults use {@link Shell#exec} with {@link RedisCommandBuilder} commands.
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Quick Start</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>{@code
 * @Container
 * static GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
 *     .withExposedPorts(6379)
 *     .withCreateContainerCmdModifier(cmd ->
 *         cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
 *
 * CacheChaos chaos = new CacheChaosProvider();
 *
 * @AfterEach void cleanup() { chaos.reset(redis); }  // surgical — safe for @AfterEach
 *
 * @Test void testLatency() {
 *     chaos.slowResponse(redis, Duration.ofMillis(200));
 *     // ... assert application handles slow cache
 * }
 * }</pre>
 *
 * @see CacheChaosConfig
 * @see com.macstab.chaos.proxy.ProxyChaosProvider
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CacheChaosProvider implements CacheChaos {

  private final CacheChaosConfig config;
  private final ProxyChaos proxy;

  /** Create provider with default configuration. */
  public CacheChaosProvider() {
    this(CacheChaosConfig.defaults(), new ProxyChaosProvider());
  }

  /**
   * Create provider with custom configuration.
   *
   * @param config cache chaos configuration (ports, proxy name)
   */
  public CacheChaosProvider(final CacheChaosConfig config) {
    this(config, new ProxyChaosProvider());
  }

  /**
   * Create provider with custom configuration and proxy (for testing).
   *
   * @param config cache chaos configuration
   * @param proxy proxy chaos provider (inject mock for unit tests)
   */
  public CacheChaosProvider(final CacheChaosConfig config, final ProxyChaos proxy) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.proxy = Objects.requireNonNull(proxy, "proxy must not be null");
  }

  // ==================== TCP-Level Faults (via Toxiproxy) ====================

  @Override
  public void slowResponse(final GenericContainer<?> container, final Duration delay) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(delay, "delay must not be null");
    if (delay.isNegative()) {
      throw new IllegalArgumentException("delay must not be negative");
    }
    validateRunning(container);
    ensureProxy(container);
    proxy.addLatency(container, config.proxyName(), delay);
    log.info("Added {}ms latency to cache responses", delay.toMillis());
  }

  @Override
  public void injectConnectionFailures(final GenericContainer<?> container, final double rate) {
    Objects.requireNonNull(container, "container must not be null");
    if (rate < 0.0 || rate > 1.0) {
      throw new IllegalArgumentException(
          String.format("rate must be in [0.0, 1.0], got: %.2f", rate));
    }
    validateRunning(container);
    ensureProxy(container);
    // timeout=1ms, not zero — ProxyChaosProvider rejects Duration.ZERO
    proxy.addTimeout(container, config.proxyName(), Duration.ofMillis(1), rate);
    log.info("Injecting {}% TCP connection failures on cache", String.format("%.0f", rate * 100));
  }

  @Override
  public void limitThroughput(final GenericContainer<?> container, final long rateKBps) {
    Objects.requireNonNull(container, "container must not be null");
    if (rateKBps <= 0) {
      throw new IllegalArgumentException("rateKBps must be positive, got: " + rateKBps);
    }
    validateRunning(container);
    ensureProxy(container);
    proxy.limitBandwidth(container, config.proxyName(), rateKBps);
    log.info("Limited cache throughput to {} KB/s", rateKBps);
  }

  @Override
  public void truncateResponses(final GenericContainer<?> container, final long bytes) {
    Objects.requireNonNull(container, "container must not be null");
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must be >= 0, got: " + bytes);
    }
    validateRunning(container);
    ensureProxy(container);
    proxy.addLimitData(container, config.proxyName(), bytes);
    log.info("Truncating cache responses after {} bytes", bytes);
  }

  @Override
  public void removeFault(final GenericContainer<?> container, final String faultName) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(faultName, "faultName must not be null");
    validateRunning(container);
    proxy.removeToxic(container, config.proxyName(), faultName);
    log.info("Removed cache fault '{}'", faultName);
  }

  @Override
  public void removeAllFaults(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateRunning(container);
    proxy.removeAllToxics(container, config.proxyName());
    log.info("Removed all cache faults (proxy stays active)");
  }

  // ==================== Redis-Level Faults (via redis-cli) ====================

  @Override
  public void forceEviction(final GenericContainer<?> container, final int percentage) {
    Objects.requireNonNull(container, "container must not be null");
    if (percentage < 1 || percentage > 100) {
      throw new IllegalArgumentException(
          String.format("percentage must be in [1, 100], got: %d", percentage));
    }
    validateRunning(container);
    execRedisCommand(container,
        RedisCommandBuilder.buildForceEvictionCommand(config.redisPort(), percentage),
        "force eviction");
    log.info("Evicted ~{}% of cache keys", percentage);
  }

  @Override
  public void limitMemory(final GenericContainer<?> container, final long bytes) {
    Objects.requireNonNull(container, "container must not be null");
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must be >= 0, got: " + bytes);
    }
    validateRunning(container);
    execRedisCommand(container,
        RedisCommandBuilder.buildSetMemoryLimitCommand(config.redisPort(), bytes),
        "set memory limit");
    log.info("Set Redis maxmemory to {} bytes", bytes);
  }

  @Override
  public void setEvictionPolicy(final GenericContainer<?> container, final String policy) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    if (policy.isBlank()) {
      throw new IllegalArgumentException("policy must not be blank");
    }
    validateRunning(container);
    execRedisCommand(container,
        RedisCommandBuilder.buildSetEvictionPolicyCommand(config.redisPort(), policy),
        "set eviction policy");
    log.info("Set Redis eviction policy to '{}'", policy);
  }

  @Override
  public void disconnectClients(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateRunning(container);
    execRedisCommand(container,
        RedisCommandBuilder.buildDisconnectClientsCommand(config.redisPort()),
        "disconnect clients");
    log.info("Disconnected all Redis clients");
  }

  @Override
  public void flushAll(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateRunning(container);
    execRedisCommand(container,
        RedisCommandBuilder.buildFlushAllCommand(config.redisPort()),
        "flush all");
    log.info("Flushed all Redis databases");
  }

  // ==================== Lifecycle ====================

  /**
   * ✅ Targeted reset — removes only the Redis proxy and its iptables rule.
   *
   * <p>The Toxiproxy process and all other proxies stay alive. Safe for {@code @AfterEach}.
   * No-op if the container is not running.
   */
  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!container.isRunning()) {
      return;
    }
    proxy.deleteProxy(container, config.proxyName());
    log.info("Reset cache chaos (deleted proxy '{}')", config.proxyName());
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    // Tools installed on demand via proxy.createProxy() — no explicit install step needed.
  }

  // ==================== Private Helpers ====================

  /**
   * Ensure the Redis Toxiproxy proxy exists for this container.
   *
   * <p>Delegates to {@link ProxyChaos#createProxy} which is idempotent — safe to call on every
   * fault injection. Platform detection happens exactly once per operation inside
   * {@code ToxiproxyOrchestrator}.
   */
  private void ensureProxy(final GenericContainer<?> container) {
    proxy.createProxy(container, config.proxyName(), config.redisPort(), config.proxyPort());
  }

  /** Assert container is running before any operation. */
  private void validateRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }

  /**
   * Execute a redis-cli command and throw on non-zero exit.
   *
   * @param container target container
   * @param command shell command string
   * @param operationName human-readable name for error messages
   */
  private void execRedisCommand(
      final GenericContainer<?> container,
      final String command,
      final String operationName) {

    try {
      final var result = Shell.exec(container, command);
      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            String.format("Failed to %s (exit %d): %s",
                operationName, result.getExitCode(),
                result.getStdout().isEmpty() ? result.getStderr() : result.getStdout()));
      }
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to " + operationName, e);
    }
  }
}
