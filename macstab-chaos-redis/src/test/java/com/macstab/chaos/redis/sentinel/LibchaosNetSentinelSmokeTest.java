/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ConnectionChaos;
import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.extension.SentinelCluster;

/**
 * Smoke test verifying {@code enableConnectionChaos=true} lifecycle wiring across an entire
 * Sentinel topology (master + replica + sentinel).
 *
 * <p>Asserts (all against the master container — same wiring runs on every cluster node):
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} on the started master includes {@code libchaos-net.so}, proving the
 *       pre-start {@link com.macstab.chaos.core.syscall.LibchaosTransport#prepare} ran on every
 *       node {@link com.macstab.chaos.redis.factory.SentinelContainerFactory} creates.
 *   <li>{@code cluster.getControl().connection()} resolves a non-null {@link ConnectionChaos} via
 *       {@code ServiceLoader}.
 *   <li>Applying one rule via the simplest verb ({@code addLatency}) produces the libchaos-net
 *       config file at {@code /tmp/.chaos-net.conf} inside the master container.
 * </ol>
 *
 * <p>Topology kept minimal (1 replica + 1 sentinel) — this is a wiring check, not a chaos
 * scenario.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisSentinel(id = "ha", replicas = 1, sentinels = 1, quorum = 1, enableConnectionChaos = true)
@DisplayName("libchaos-net sentinel wiring smoke")
class LibchaosNetSentinelSmokeTest {

  @Test
  @DisplayName("LD_PRELOAD on the master container includes libchaos-net.so")
  void shouldPreloadLibchaosNetOnMaster(final SentinelCluster cluster) {
    final GenericContainer<?> master = cluster.getMasterContainer();
    final String ldPreload = master.getEnvMap().getOrDefault("LD_PRELOAD", "");
    assertThat(ldPreload).contains("libchaos-net.so");
  }

  @Test
  @DisplayName("ControlFacade.connection() returns a non-null ConnectionChaos via ServiceLoader")
  void shouldExposeConnectionChaosViaControlFacade(final SentinelCluster cluster) {
    final ConnectionChaos connection = cluster.getControl().connection();
    assertThat(connection).isNotNull();
  }

  @Test
  @DisplayName("Applying one rule writes the libchaos-net config file inside the master container")
  void shouldWriteConfigFileWhenRuleApplied(final SentinelCluster cluster) throws Exception {
    final GenericContainer<?> master = cluster.getMasterContainer();

    cluster.getControl().connection().addLatency(master, "redis-master:6379", Duration.ofMillis(10));

    final var result = master.execInContainer("test", "-s", "/tmp/.chaos-net.conf");
    assertThat(result.getExitCode())
        .as("libchaos-net config file should exist and be non-empty after addLatency")
        .isEqualTo(0);

    cluster.getControl().connection().removeAllToxics(master, "redis-master:6379");
  }
}
