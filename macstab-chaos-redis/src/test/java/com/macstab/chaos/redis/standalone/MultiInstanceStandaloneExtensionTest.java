/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.api.StandaloneRedis;

/**
 * Exercises {@code StandaloneStartupOrchestrator} parallel startup path with multiple instances.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code StandaloneStartupOrchestrator.submitStartupTasks()} — parallel execution
 *   <li>{@code StandaloneStartupOrchestrator.collectResults()} — result aggregation
 *   <li>Declaration-order preservation via {@code LinkedHashMap}
 *   <li>Data isolation between instances
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisStandalone(id = "cache", version = "7.4")
@RedisStandalone(id = "session", version = "7.4")
@DisplayName("StandaloneStartupOrchestrator — Multi-Instance Integration")
class MultiInstanceStandaloneExtensionTest {

  @Nested
  @DisplayName("Parallel startup")
  class ParallelStartupTests {

    @Test
    @DisplayName("should start all instances and return correct count")
    void shouldStartAllInstances(final List<StandaloneRedis> all) {
      assertThat(all).hasSize(2);
      all.forEach(
          r -> {
            assertThat(r.host()).isNotBlank();
            assertThat(r.port()).isGreaterThan(0);
          });
    }

    @Test
    @DisplayName("should preserve annotation declaration order")
    void shouldPreserveDeclarationOrder(final List<StandaloneRedis> all) {
      assertThat(all.get(0)).isEqualTo(RedisStandalone.INSTANCE.get("cache"));
      assertThat(all.get(1)).isEqualTo(RedisStandalone.INSTANCE.get("session"));
    }
  }

  @Nested
  @DisplayName("Data isolation")
  class DataIsolationTests {

    @Test
    @DisplayName("should isolate data between instances")
    void shouldIsolateData() throws Exception {
      final StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
      final StandaloneRedis session = RedisStandalone.INSTANCE.get("session");

      final var cacheClient =
          io.lettuce.core.RedisClient.create(
              io.lettuce.core.RedisURI.builder()
                  .withHost(cache.host())
                  .withPort(cache.port())
                  .build());
      final var sessionClient =
          io.lettuce.core.RedisClient.create(
              io.lettuce.core.RedisURI.builder()
                  .withHost(session.host())
                  .withPort(session.port())
                  .build());

      try (final var cc = cacheClient.connect();
          final var sc = sessionClient.connect()) {
        cc.sync().set("shared-key", "from-cache");
        sc.sync().set("shared-key", "from-session");

        assertThat(cc.sync().get("shared-key")).isEqualTo("from-cache");
        assertThat(sc.sync().get("shared-key")).isEqualTo("from-session");
      } finally {
        cacheClient.shutdown();
        sessionClient.shutdown();
      }
    }
  }

  @Nested
  @DisplayName("Programmatic access")
  class ProgrammaticAccessTests {

    @Test
    @DisplayName("should access individual instances by id")
    void shouldAccessById() {
      assertThat(RedisStandalone.INSTANCE.get("cache")).isNotNull();
      assertThat(RedisStandalone.INSTANCE.get("session")).isNotNull();
    }

    @Test
    @DisplayName("instances should have different ports")
    void shouldHaveDifferentPorts() {
      final StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
      final StandaloneRedis session = RedisStandalone.INSTANCE.get("session");
      assertThat(cache.port()).isNotEqualTo(session.port());
    }
  }
}
