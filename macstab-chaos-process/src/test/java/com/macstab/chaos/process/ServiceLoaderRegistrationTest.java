/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.ProcessChaos;
import com.macstab.chaos.core.spi.ChaosProviderRegistry;

class ServiceLoaderRegistrationTest {
  @Test
  void shouldLoadCgroupsProcessChaosViaServiceLoader() {
    final ProcessChaos process = ChaosProviderRegistry.getProcessChaos();
    assertThat(process).isInstanceOf(CgroupsProcessChaos.class);
  }
}
