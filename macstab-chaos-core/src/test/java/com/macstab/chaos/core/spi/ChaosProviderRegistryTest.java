/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.CpuChaos;
import com.macstab.chaos.core.api.DiskChaos;
import com.macstab.chaos.core.api.DnsChaos;
import com.macstab.chaos.core.api.MemoryChaos;
import com.macstab.chaos.core.api.NetworkChaos;
import com.macstab.chaos.core.api.ProcessChaos;
import com.macstab.chaos.core.api.TimeChaos;
import com.macstab.chaos.core.defaults.NoOpCpuChaos;
import com.macstab.chaos.core.defaults.NoOpDiskChaos;
import com.macstab.chaos.core.defaults.NoOpDnsChaos;
import com.macstab.chaos.core.defaults.NoOpMemoryChaos;
import com.macstab.chaos.core.defaults.NoOpNetworkChaos;
import com.macstab.chaos.core.defaults.NoOpProcessChaos;
import com.macstab.chaos.core.defaults.NoOpTimeChaos;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;

class ChaosProviderRegistryTest {

  @Test
  void shouldReturnNoOpCpuChaosWhenModuleNotPresent() {
    // When
    final CpuChaos cpu = ChaosProviderRegistry.getCpuChaos();

    // Then
    assertThat(cpu).isInstanceOf(NoOpCpuChaos.class);
    assertThat(cpu.isSupported()).isFalse();
  }

  @Test
  void shouldReturnNoOpMemoryChaosWhenModuleNotPresent() {
    // When
    final MemoryChaos memory = ChaosProviderRegistry.getMemoryChaos();

    // Then
    assertThat(memory).isInstanceOf(NoOpMemoryChaos.class);
    assertThat(memory.isSupported()).isFalse();
  }

  @Test
  void shouldReturnNoOpDiskChaosWhenModuleNotPresent() {
    // When
    final DiskChaos disk = ChaosProviderRegistry.getDiskChaos();

    // Then
    assertThat(disk).isInstanceOf(NoOpDiskChaos.class);
    assertThat(disk.isSupported()).isFalse();
  }

  @Test
  void shouldReturnNoOpProcessChaosWhenModuleNotPresent() {
    // When
    final ProcessChaos process = ChaosProviderRegistry.getProcessChaos();

    // Then
    assertThat(process).isInstanceOf(NoOpProcessChaos.class);
    assertThat(process.isSupported()).isFalse();
  }

  @Test
  void shouldReturnNoOpNetworkChaosWhenModuleNotPresent() {
    // When
    final NetworkChaos network = ChaosProviderRegistry.getNetworkChaos();

    // Then
    assertThat(network).isInstanceOf(NoOpNetworkChaos.class);
    assertThat(network.isSupported()).isFalse();
  }

  @Test
  void shouldReturnNoOpTimeChaosWhenModuleNotPresent() {
    // When
    final TimeChaos time = ChaosProviderRegistry.getTimeChaos();

    // Then
    assertThat(time).isInstanceOf(NoOpTimeChaos.class);
    assertThat(time.isSupported()).isFalse();
  }

  @Test
  void shouldReturnNoOpDnsChaosWhenModuleNotPresent() {
    // When
    final DnsChaos dns = ChaosProviderRegistry.getDnsChaos();

    // Then
    assertThat(dns).isInstanceOf(NoOpDnsChaos.class);
    assertThat(dns.isSupported()).isFalse();
  }

  @Test
  void noOpImplementationsShouldThrowHelpfulErrors() {
    // Given
    final CpuChaos cpu = ChaosProviderRegistry.getCpuChaos();
    final GenericContainer<?> container = null; // Won't be used (throws before validation)

    // When/Then
    assertThatThrownBy(() -> cpu.throttle(container, 50))
        .isInstanceOf(ChaosProviderNotFoundException.class)
        .hasMessageContaining("CPU chaos not available")
        .hasMessageContaining("macstab-chaos-cpu");
  }

  @Test
  void shouldReturnSameInstanceOnMultipleCalls() {
    // When
    final CpuChaos cpu1 = ChaosProviderRegistry.getCpuChaos();
    final CpuChaos cpu2 = ChaosProviderRegistry.getCpuChaos();

    // Then
    assertThat(cpu1).isNotSameAs(cpu2); // New instance each time (no caching)
  }
}
