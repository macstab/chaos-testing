/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.facade;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.CacheChaos;
import com.macstab.chaos.core.api.ConnectionChaos;
import com.macstab.chaos.core.api.CpuChaos;
import com.macstab.chaos.core.api.DiskChaos;
import com.macstab.chaos.core.api.DnsChaos;
import com.macstab.chaos.core.api.FilesystemChaos;
import com.macstab.chaos.core.api.MemoryChaos;
import com.macstab.chaos.core.api.NetworkChaos;
import com.macstab.chaos.core.api.ProcessChaos;
import com.macstab.chaos.core.api.TimeChaos;
import com.macstab.chaos.core.spi.ChaosProviderRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * Facade that provides access to all chaos providers for a single container. Lazily resolves
 * providers via {@link com.macstab.chaos.core.spi.ChaosProviderRegistry}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ChaosController {

  private final GenericContainer<?> container;

  // Probabilistic chaos context (null = deterministic mode)
  private final Double probabilityRate;
  private final Long probabilitySeed;

  // Lazy-loaded chaos providers
  private CpuChaos cpu;
  private MemoryChaos memory;
  private DiskChaos disk;
  private ProcessChaos process;
  private NetworkChaos network;
  private TimeChaos time;
  private DnsChaos dns;
  private ConnectionChaos connection;
  private CacheChaos cache;
  private FilesystemChaos filesystem;

  /**
   * Create chaos controller for container (deterministic mode).
   *
   * @param container target container (must be running)
   */
  public ChaosController(final GenericContainer<?> container) {
    this(container, null, null);
  }

  /**
   * Create chaos controller with probabilistic context.
   *
   * @param container target container
   * @param probabilityRate execution probability (null = deterministic)
   * @param probabilitySeed random seed (null if rate is null)
   */
  private ChaosController(
      final GenericContainer<?> container,
      final Double probabilityRate,
      final Long probabilitySeed) {
    this.container = Objects.requireNonNull(container, "container must not be null");
    this.probabilityRate = probabilityRate;
    this.probabilitySeed = probabilitySeed;
  }

  /**
   * Create probabilistic chaos controller.
   *
   * <p>Returns new controller that applies chaos operations probabilistically based on rate and
   * seed.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // 30% chance of chaos (repeatable with seed=42)
   * chaos.withProbability(0.3, 42)
   *   .cpu().throttle(container, 50);
   *
   * // Different seed = different pattern
   * chaos.withProbability(0.3, 99)
   *   .cpu().throttle(container, 50);
   *
   * // Combined with patterns
   * chaos.withProbability(0.5, 42)
   *   .cpu().throttle()
   *     .rampFrom(10).to(90).over(Duration.ofSeconds(60))
   *     .execute(container);
   * }</pre>
   *
   * @param rate execution probability (0.0 = never, 1.0 = always)
   * @param seed random seed for repeatability
   * @return new controller with probabilistic context
   * @throws IllegalArgumentException if rate not in [0.0, 1.0]
   */
  public ChaosController withProbability(final double rate, final long seed) {
    if (rate < 0.0 || rate > 1.0) {
      throw new IllegalArgumentException("rate must be in [0.0, 1.0], got: " + rate);
    }
    return new ChaosController(container, rate, seed);
  }

  /**
   * Get CPU chaos provider.
   *
   * @return CPU chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public CpuChaos cpu() {
    if (cpu == null) {
      cpu = ChaosProviderRegistry.getCpuChaos();
    }

    // Apply probabilistic wrapper if rate is set
    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(cpu, probabilityRate, probabilitySeed);
    }

    return cpu;
  }

  /**
   * Get memory chaos provider.
   *
   * @return memory chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public MemoryChaos memory() {
    if (memory == null) {
      memory = ChaosProviderRegistry.getMemoryChaos();
    }

    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(memory, probabilityRate, probabilitySeed);
    }

    return memory;
  }

  /**
   * Get disk chaos provider.
   *
   * @return disk chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public DiskChaos disk() {
    if (disk == null) {
      disk = ChaosProviderRegistry.getDiskChaos();
    }

    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(disk, probabilityRate, probabilitySeed);
    }

    return disk;
  }

  /**
   * Get process chaos provider.
   *
   * @return process chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public ProcessChaos process() {
    if (process == null) {
      process = ChaosProviderRegistry.getProcessChaos();
    }

    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(process, probabilityRate, probabilitySeed);
    }

    return process;
  }

  /**
   * Get network chaos provider.
   *
   * @return network chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public NetworkChaos network() {
    if (network == null) {
      network = ChaosProviderRegistry.getNetworkChaos();
    }

    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(network, probabilityRate, probabilitySeed);
    }

    return network;
  }

  /**
   * Get time chaos provider.
   *
   * @return time chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public TimeChaos time() {
    if (time == null) {
      time = ChaosProviderRegistry.getTimeChaos();
    }

    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(time, probabilityRate, probabilitySeed);
    }

    return time;
  }

  /**
   * Get DNS chaos provider.
   *
   * @return DNS chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public DnsChaos dns() {
    if (dns == null) {
      dns = ChaosProviderRegistry.getDnsChaos();
    }

    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(dns, probabilityRate, probabilitySeed);
    }

    return dns;
  }

  /**
   * Get connection chaos provider.
   *
   * @return connection chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public ConnectionChaos connection() {
    if (connection == null) {
      connection = ChaosProviderRegistry.getConnectionChaos();
    }

    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(connection, probabilityRate, probabilitySeed);
    }

    return connection;
  }

  /**
   * Get cache chaos provider.
   *
   * @return cache chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public CacheChaos cache() {
    if (cache == null) {
      cache = ChaosProviderRegistry.getCacheChaos();
    }

    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(cache, probabilityRate, probabilitySeed);
    }

    return cache;
  }

  /**
   * Get filesystem chaos provider.
   *
   * @return filesystem chaos (real implementation, no-op, or probabilistic wrapper)
   */
  public FilesystemChaos filesystem() {
    if (filesystem == null) {
      filesystem = ChaosProviderRegistry.getFilesystemChaos();
    }

    if (probabilityRate != null) {
      return ProbabilisticWrapper.wrap(filesystem, probabilityRate, probabilitySeed);
    }

    return filesystem;
  }

  /**
   * Reset all chaos effects (CPU, memory, network, etc.).
   *
   * <p>Calls {@link com.macstab.chaos.core.api.ChaosProvider#reset(GenericContainer)} on all loaded
   * providers.
   */
  public void resetAll() {
    if (cpu != null) {
      cpu.reset(container);
    }
    if (memory != null) {
      memory.reset(container);
    }
    if (disk != null) {
      disk.reset(container);
    }
    if (process != null) {
      process.reset(container);
    }
    if (network != null) {
      network.reset(container);
    }
    if (time != null) {
      time.reset(container);
    }
    if (dns != null) {
      dns.reset(container);
    }
    if (connection != null) {
      connection.reset(container);
    }
    if (cache != null) {
      cache.reset(container);
    }
    if (filesystem != null) {
      filesystem.reset(container);
    }
  }

  /**
   * Get target container.
   *
   * @return target container
   */
  public GenericContainer<?> getContainer() {
    return container;
  }
}
