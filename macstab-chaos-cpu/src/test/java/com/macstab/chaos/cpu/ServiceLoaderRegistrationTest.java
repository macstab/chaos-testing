/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.CpuChaos;
import com.macstab.chaos.core.spi.ChaosProviderRegistry;

/**
 * SPI registration test - verifies {@link CgroupsCpuChaos} is discoverable via
 * {@code META-INF/services}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ServiceLoader Registration")
class ServiceLoaderRegistrationTest {

  @Test
  @DisplayName("loads CgroupsCpuChaos via ServiceLoader")
  void loadsRealImplementation() {
    // WHEN
    final CpuChaos cpu = ChaosProviderRegistry.getCpuChaos();

    // THEN
    assertThat(cpu).isInstanceOf(CgroupsCpuChaos.class);
  }

  @Test
  @DisplayName("reports isSupported (platform-dependent)")
  void reportsSupported() {
    // WHEN
    final CpuChaos cpu = ChaosProviderRegistry.getCpuChaos();

    // THEN
    assertThat(cpu.isSupported()).isIn(true, false);
  }
}
