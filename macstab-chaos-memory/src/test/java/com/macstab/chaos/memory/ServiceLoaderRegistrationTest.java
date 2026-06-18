/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.MemoryChaos;
import com.macstab.chaos.core.spi.ChaosProviderRegistry;
import com.macstab.chaos.memory.strategy.cgroups.CgroupsMemoryChaos;

class ServiceLoaderRegistrationTest {
  @Test
  void shouldLoadCgroupsMemoryChaosViaServiceLoader() {
    final MemoryChaos memory = ChaosProviderRegistry.getMemoryChaos();
    assertThat(memory).isInstanceOf(CgroupsMemoryChaos.class);
  }
}
