/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.cache.redis.RedisCacheChaosProvider;
import com.macstab.chaos.core.api.CacheChaos;

/**
 * Verify ServiceLoader registration for {@link RedisCacheChaosProvider}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class ServiceLoaderRegistrationTest {

  @Test
  @DisplayName("should load RedisCacheChaosProvider via ServiceLoader")
  void shouldLoadRedisCacheChaosProviderViaServiceLoader() {
    final ServiceLoader<CacheChaos> loader = ServiceLoader.load(CacheChaos.class);
    final CacheChaos chaos = loader.findFirst().orElse(null);
    assertThat(chaos).isNotNull();
    assertThat(chaos).isInstanceOf(RedisCacheChaosProvider.class);
    assertThat(chaos.isSupported()).isTrue();
  }
}
