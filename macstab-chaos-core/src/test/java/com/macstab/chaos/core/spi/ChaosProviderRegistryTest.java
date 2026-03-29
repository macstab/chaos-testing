/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.*;
import com.macstab.chaos.core.defaults.*;

/**
 * Tests for {@link ChaosProviderRegistry} - service loader facade.
 *
 * <p>Validates that all chaos provider getters return no-op implementations when no real provider
 * is registered via ServiceLoader.
 */
@DisplayName("ChaosProviderRegistry - Service Loader Facade")
class ChaosProviderRegistryTest {

  @Test
  @DisplayName("getCpuChaos() should return no-op implementation when no provider registered")
  void getCpuChaos_shouldReturnNoOp() {
    CpuChaos chaos = ChaosProviderRegistry.getCpuChaos();
    assertThat(chaos).isInstanceOf(NoOpCpuChaos.class);
  }

  @Test
  @DisplayName("getMemoryChaos() should return no-op implementation")
  void getMemoryChaos_shouldReturnNoOp() {
    MemoryChaos chaos = ChaosProviderRegistry.getMemoryChaos();
    assertThat(chaos).isInstanceOf(NoOpMemoryChaos.class);
  }

  @Test
  @DisplayName("getDiskChaos() should return no-op implementation")
  void getDiskChaos_shouldReturnNoOp() {
    DiskChaos chaos = ChaosProviderRegistry.getDiskChaos();
    assertThat(chaos).isInstanceOf(NoOpDiskChaos.class);
  }

  @Test
  @DisplayName("getProcessChaos() should return no-op implementation")
  void getProcessChaos_shouldReturnNoOp() {
    ProcessChaos chaos = ChaosProviderRegistry.getProcessChaos();
    assertThat(chaos).isInstanceOf(NoOpProcessChaos.class);
  }

  @Test
  @DisplayName("getNetworkChaos() should return no-op implementation")
  void getNetworkChaos_shouldReturnNoOp() {
    NetworkChaos chaos = ChaosProviderRegistry.getNetworkChaos();
    assertThat(chaos).isInstanceOf(NoOpNetworkChaos.class);
  }

  @Test
  @DisplayName("getTimeChaos() should return no-op implementation")
  void getTimeChaos_shouldReturnNoOp() {
    TimeChaos chaos = ChaosProviderRegistry.getTimeChaos();
    assertThat(chaos).isInstanceOf(NoOpTimeChaos.class);
  }

  @Test
  @DisplayName("getDnsChaos() should return no-op implementation")
  void getDnsChaos_shouldReturnNoOp() {
    DnsChaos chaos = ChaosProviderRegistry.getDnsChaos();
    assertThat(chaos).isInstanceOf(NoOpDnsChaos.class);
  }

  @Test
  @DisplayName("getConnectionChaos() should return no-op implementation")
  void getConnectionChaos_shouldReturnNoOp() {
    ConnectionChaos chaos = ChaosProviderRegistry.getConnectionChaos();
    assertThat(chaos).isInstanceOf(NoOpConnectionChaos.class);
  }

  @Test
  @DisplayName("getCacheChaos() should return no-op implementation")
  void getCacheChaos_shouldReturnNoOp() {
    CacheChaos chaos = ChaosProviderRegistry.getCacheChaos();
    assertThat(chaos).isInstanceOf(NoOpCacheChaos.class);
  }

  @Test
  @DisplayName("getFilesystemChaos() should return no-op implementation")
  void getFilesystemChaos_shouldReturnNoOp() {
    FilesystemChaos chaos = ChaosProviderRegistry.getFilesystemChaos();
    assertThat(chaos).isInstanceOf(NoOpFilesystemChaos.class);
  }

  @Test
  @DisplayName("All getters should return non-null instances")
  void allGetters_shouldReturnNonNull() {
    assertThat(ChaosProviderRegistry.getCpuChaos()).isNotNull();
    assertThat(ChaosProviderRegistry.getMemoryChaos()).isNotNull();
    assertThat(ChaosProviderRegistry.getDiskChaos()).isNotNull();
    assertThat(ChaosProviderRegistry.getProcessChaos()).isNotNull();
    assertThat(ChaosProviderRegistry.getNetworkChaos()).isNotNull();
    assertThat(ChaosProviderRegistry.getTimeChaos()).isNotNull();
    assertThat(ChaosProviderRegistry.getDnsChaos()).isNotNull();
    assertThat(ChaosProviderRegistry.getConnectionChaos()).isNotNull();
    assertThat(ChaosProviderRegistry.getCacheChaos()).isNotNull();
    assertThat(ChaosProviderRegistry.getFilesystemChaos()).isNotNull();
  }

  @Test
  @DisplayName("Multiple calls should return consistent instances")
  void multipleCalls_shouldReturnConsistentInstances() {
    // ServiceLoader may cache or create new instances - just verify non-null
    CpuChaos first = ChaosProviderRegistry.getCpuChaos();
    CpuChaos second = ChaosProviderRegistry.getCpuChaos();

    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(first.getClass()).isEqualTo(second.getClass());
  }
}
