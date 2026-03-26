/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.annotation.Resources;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.api.Redis;
import com.macstab.chaos.redis.api.RedisTopology;
import com.macstab.chaos.redis.api.StandaloneRedis;

/**
 * Integration tests for Redis INSTANCE programmatic access pattern.
 *
 * <p><strong>Tests:</strong>
 *
 * <ul>
 *   <li>Per-annotation INSTANCE access (type-safe)
 *   <li>Base interface unified access
 *   <li>Parameter injection compatibility
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@DisplayName("Redis INSTANCE Access Pattern Tests")
@RedisStandalone(id="cache")
@Resources(memory="256M", cpus="1")
class RedisInstanceAccessTest {

  @Test
  @DisplayName("should access via annotation INSTANCE (type-safe)")
  void shouldAccessViaAnnotationInstance() {
    // Per-annotation INSTANCE (primary UX - 95% of cases)
    StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
    
    assertThat(cache).isNotNull();
    assertThat(cache.host()).isEqualTo("localhost");
    assertThat(cache.port()).isGreaterThan(1024);
    assertThat(cache.getTopology()).isEqualTo(RedisTopology.STANDALONE);
  }

  @Test
  @DisplayName("should access via base interface helper (unified)")
  void shouldAccessViaBaseInterfaceHelper() {
    // Base interface helper (unified access - 5% of cases)
    Redis cache = Redis.get("cache");
    
    assertThat(cache).isNotNull();
    assertThat(cache).isInstanceOf(StandaloneRedis.class);
    assertThat(cache.getHost()).isEqualTo("localhost");
    assertThat(cache.getPort()).isGreaterThan(1024);
  }

  @Test
  @DisplayName("should get all Redis instances via base interface")
  void shouldGetAllViaBaseInterface() {
    List<Redis> all = Redis.getAll();
    
    assertThat(all).hasSize(1);
    assertThat(all.get(0)).isInstanceOf(StandaloneRedis.class);
  }

  @Test
  @DisplayName("should support parameter injection (backward compatible)")
  void shouldSupportParameterInjection(StandaloneRedis info) {
    assertThat(info).isNotNull();
    assertThat(info.host()).isEqualTo("localhost");
    assertThat(info.port()).isGreaterThan(1024);
    
    // Verify INSTANCE returns same object
    StandaloneRedis fromInstance = RedisStandalone.INSTANCE.get("cache");
    assertThat(fromInstance).isEqualTo(info);
  }

  @Test
  @DisplayName("should handle type discrimination with pattern matching")
  void shouldHandleTypeDiscrimination() {
    Redis redis = Redis.get("cache");
    
    String result = switch (redis) {
      case StandaloneRedis s -> "Standalone: " + s.host() + ":" + s.port();
      case com.macstab.chaos.redis.api.SentinelRedis s -> "Sentinel: " + s.masterName();
    };
    
    assertThat(result).startsWith("Standalone: localhost:");
  }
}
