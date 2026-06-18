/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.DnsChaos;
import com.macstab.chaos.core.spi.ChaosProviderRegistry;
import com.macstab.chaos.dns.strategy.iptables.IptablesDnsChaos;

class ServiceLoaderRegistrationTest {
  @Test
  void shouldLoadIptablesDnsChaosViaServiceLoader() {
    final DnsChaos dns = ChaosProviderRegistry.getDnsChaos();
    assertThat(dns).isInstanceOf(IptablesDnsChaos.class);
  }
}
