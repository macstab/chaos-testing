/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.connection.strategy.toxiproxy.ToxiproxyConnectionChaos;
import com.macstab.chaos.core.api.ConnectionChaos;

/**
 * Verify ServiceLoader registration for {@link ToxiproxyConnectionChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class ServiceLoaderRegistrationTest {

  @Test
  @DisplayName("should load ToxiproxyConnectionChaos via ServiceLoader")
  void shouldLoadToxiproxyConnectionChaosViaServiceLoader() {
    final ServiceLoader<ConnectionChaos> loader = ServiceLoader.load(ConnectionChaos.class);

    final ConnectionChaos chaos = loader.findFirst().orElse(null);

    assertThat(chaos).isNotNull();
    assertThat(chaos).isInstanceOf(ToxiproxyConnectionChaos.class);
  }
}
