/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ConnectionChaos;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.api.StandaloneRedis;
import com.macstab.chaos.redis.control.ControlFacade;
import com.macstab.chaos.redis.extension.RedisContainerExtension;

/**
 * Smoke test verifying {@code enableConnectionChaos=true} lifecycle wiring on a single standalone
 * Redis container.
 *
 * <p>Asserts:
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} on the started container includes {@code libchaos-net.so}, proving the
 *       pre-start {@link com.macstab.chaos.core.syscall.LibchaosTransport#prepare} ran in the
 *       correct window.
 *   <li>{@code ControlFacade.connection()} resolves a non-null {@link ConnectionChaos} via {@code
 *       ServiceLoader}, proving the SPI wiring is intact.
 *   <li>Applying one rule via the simplest verb ({@code addLatency}) produces the libchaos-net
 *       config file inside the container at {@code /tmp/.chaos-net.conf}, proving the rule actually
 *       reached the {@code .so}.
 * </ol>
 *
 * <p>This test is intentionally narrow — coverage of every verb belongs in the connection module's
 * own integration suite, not here.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisStandalone(id = "test", enableConnectionChaos = true)
@DisplayName("libchaos-net standalone wiring smoke")
class LibchaosNetStandaloneSmokeTest {

  @Test
  @DisplayName("LD_PRELOAD on the started container includes libchaos-net.so")
  void shouldPreloadLibchaosNet() {
    final GenericContainer<?> container = RedisContainerExtension.getContainerInstance("test");
    final String ldPreload = container.getEnvMap().getOrDefault("LD_PRELOAD", "");
    assertThat(ldPreload).contains("libchaos-net.so");
  }

  @Test
  @DisplayName("ControlFacade.connection() returns a non-null ConnectionChaos via ServiceLoader")
  void shouldExposeConnectionChaosViaControlFacade() {
    final GenericContainer<?> container = RedisContainerExtension.getContainerInstance("test");
    final ControlFacade control = ControlFacade.forStandalone(container);
    final ConnectionChaos connection = control.connection();
    assertThat(connection).isNotNull();
  }

  @Test
  @DisplayName("Applying one rule writes the libchaos-net config file inside the container")
  void shouldWriteConfigFileWhenRuleApplied(final StandaloneRedis redis) throws Exception {
    final GenericContainer<?> container = RedisContainerExtension.getContainerInstance("test");
    final ControlFacade control = ControlFacade.forStandalone(container);

    control
        .connection()
        .addLatency(container, redis.host() + ":" + redis.port(), Duration.ofMillis(10));

    final var result = container.execInContainer("test", "-s", "/tmp/.chaos-net.conf");
    assertThat(result.getExitCode())
        .as("libchaos-net config file should exist and be non-empty after addLatency")
        .isEqualTo(0);

    control.connection().removeAllToxics(container, redis.host() + ":" + redis.port());
  }
}
