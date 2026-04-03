/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.spi;

import java.util.Comparator;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

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
import com.macstab.chaos.core.defaults.NoOpCacheChaos;
import com.macstab.chaos.core.defaults.NoOpConnectionChaos;
import com.macstab.chaos.core.defaults.NoOpCpuChaos;
import com.macstab.chaos.core.defaults.NoOpDiskChaos;
import com.macstab.chaos.core.defaults.NoOpDnsChaos;
import com.macstab.chaos.core.defaults.NoOpFilesystemChaos;
import com.macstab.chaos.core.defaults.NoOpMemoryChaos;
import com.macstab.chaos.core.defaults.NoOpNetworkChaos;
import com.macstab.chaos.core.defaults.NoOpProcessChaos;
import com.macstab.chaos.core.defaults.NoOpTimeChaos;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ChaosProviderRegistry {

  private ChaosProviderRegistry() {
    // Utility class
  }

  /**
   * Get CPU chaos provider (real implementation or no-op).
   *
   * @return CPU chaos provider
   */
  public static CpuChaos getCpuChaos() {
    return loadProvider(CpuChaos.class, new NoOpCpuChaos());
  }

  /**
   * Get memory chaos provider (real implementation or no-op).
   *
   * @return memory chaos provider
   */
  public static MemoryChaos getMemoryChaos() {
    return loadProvider(MemoryChaos.class, new NoOpMemoryChaos());
  }

  /**
   * Get disk chaos provider (real implementation or no-op).
   *
   * @return disk chaos provider
   */
  public static DiskChaos getDiskChaos() {
    return loadProvider(DiskChaos.class, new NoOpDiskChaos());
  }

  /**
   * Get process chaos provider (real implementation or no-op).
   *
   * @return process chaos provider
   */
  public static ProcessChaos getProcessChaos() {
    return loadProvider(ProcessChaos.class, new NoOpProcessChaos());
  }

  /**
   * Get network chaos provider (real implementation or no-op).
   *
   * @return network chaos provider
   */
  public static NetworkChaos getNetworkChaos() {
    return loadProvider(NetworkChaos.class, new NoOpNetworkChaos());
  }

  /**
   * Get time chaos provider (real implementation or no-op).
   *
   * @return time chaos provider
   */
  public static TimeChaos getTimeChaos() {
    return loadProvider(TimeChaos.class, new NoOpTimeChaos());
  }

  /**
   * Get DNS chaos provider (real implementation or no-op).
   *
   * @return DNS chaos provider
   */
  public static DnsChaos getDnsChaos() {
    return loadProvider(DnsChaos.class, new NoOpDnsChaos());
  }

  /**
   * Get connection chaos provider (real implementation or no-op).
   *
   * @return connection chaos provider
   */
  public static ConnectionChaos getConnectionChaos() {
    return loadProvider(ConnectionChaos.class, new NoOpConnectionChaos());
  }

  /**
   * Get cache chaos provider (real implementation or no-op).
   *
   * @return cache chaos provider
   */
  public static CacheChaos getCacheChaos() {
    return loadProvider(CacheChaos.class, new NoOpCacheChaos());
  }

  /**
   * Get filesystem chaos provider (real implementation or no-op).
   *
   * @return filesystem chaos provider
   */
  public static FilesystemChaos getFilesystemChaos() {
    return loadProvider(FilesystemChaos.class, new NoOpFilesystemChaos());
  }

  /**
   * Load provider via ServiceLoader, selecting the one with the lowest {@code priority()}.
   *
   * <p>All discovered providers are sorted by {@link com.macstab.chaos.core.api.ChaosProvider#priority()}.
   * The provider with the lowest value wins. NoOp implementations return {@code Integer.MAX_VALUE}
   * and are therefore always last. If no provider is found at all, {@code defaultImpl} is returned.
   *
   * @param <T> provider type (must extend ChaosProvider)
   * @param serviceClass service interface class
   * @param defaultImpl default no-op implementation (returned when ServiceLoader finds nothing)
   * @return provider with lowest priority, or default
   */
  @SuppressWarnings("unchecked")
  private static <T> T loadProvider(final Class<T> serviceClass, final T defaultImpl) {
    Objects.requireNonNull(serviceClass, "serviceClass must not be null");
    Objects.requireNonNull(defaultImpl, "defaultImpl must not be null");

    final ServiceLoader<T> loader = ServiceLoader.load(serviceClass);

    return StreamSupport.stream(loader.spliterator(), false)
        .sorted(Comparator.comparingInt(p -> ((com.macstab.chaos.core.api.ChaosProvider) p).priority()))
        .findFirst()
        .orElse(defaultImpl);
  }
}
