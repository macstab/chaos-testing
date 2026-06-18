/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.DiskChaos;
import com.macstab.chaos.core.spi.ChaosProviderRegistry;

class ServiceLoaderRegistrationTest {
  @Test
  void shouldLoadCgroupsDiskChaosViaServiceLoader() {
    final DiskChaos disk = ChaosProviderRegistry.getDiskChaos();
    assertThat(disk).isInstanceOf(CgroupsDiskChaos.class);
  }
}
