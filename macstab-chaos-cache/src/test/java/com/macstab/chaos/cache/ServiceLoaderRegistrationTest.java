/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.CacheChaos;

/**
 * Verify ServiceLoader registration for {@link ToxiproxyCacheChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class ServiceLoaderRegistrationTest {

  @Test
  @DisplayName("should load ToxiproxyCacheChaos via ServiceLoader")
  void shouldLoadToxiproxyCacheChaosViaServiceLoader() {
    final ServiceLoader<CacheChaos> loader = ServiceLoader.load(CacheChaos.class);

    final CacheChaos chaos = loader.findFirst().orElse(null);

    assertThat(chaos).isNotNull();
    assertThat(chaos).isInstanceOf(ToxiproxyCacheChaos.class);
  }
}
