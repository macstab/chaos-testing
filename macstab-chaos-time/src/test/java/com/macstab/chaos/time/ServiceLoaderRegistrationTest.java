/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.TimeChaos;
import com.macstab.chaos.core.spi.ChaosProviderRegistry;

class ServiceLoaderRegistrationTest {
  @Test
  void shouldLoadLibfaketimeTimeChaosViaServiceLoader() {
    final TimeChaos time = ChaosProviderRegistry.getTimeChaos();
    assertThat(time).isInstanceOf(LibfaketimeTimeChaos.class);
  }
}
