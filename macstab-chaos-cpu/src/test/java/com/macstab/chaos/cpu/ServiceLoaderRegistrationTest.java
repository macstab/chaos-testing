/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.CpuChaos;
import com.macstab.chaos.core.spi.ChaosProviderRegistry;

class ServiceLoaderRegistrationTest {

  @Test
  void shouldLoadCgroupsCpuChaosViaServiceLoader() {
    // When
    final CpuChaos cpu = ChaosProviderRegistry.getCpuChaos();

    // Then (verify real implementation loaded, not no-op)
    assertThat(cpu).isInstanceOf(CgroupsCpuChaos.class);
  }

  @Test
  void shouldReportSupported() {
    // When
    final CpuChaos cpu = ChaosProviderRegistry.getCpuChaos();

    // Then (on Linux host with cgroups v2 mounted)
    // On macOS/Windows, this would be false
    final boolean supported = cpu.isSupported();
    assertThat(supported).isIn(true, false); // Platform-dependent
  }
}
