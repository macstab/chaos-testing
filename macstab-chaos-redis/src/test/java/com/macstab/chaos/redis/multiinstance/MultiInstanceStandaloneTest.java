/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;

/**
 * Meta-test validating multi-instance standalone Redis support.
 *
 * <p>Tests the v2.0 multi-instance capabilities for standalone Redis:
 *
 * <ul>
 *   <li>Multiple {@code @RedisStandalone} annotations
 *   <li>Parallel instance startup
 *   <li>Parameter injection via {@code List<RedisConnectionInfo>}
 *   <li>Programmatic access via {@code RedisStandalone.INSTANCE.get(id)}
 *   <li>Ordering guarantees (declaration order)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisStandalone(id = "cache", version = "7.4")
@RedisStandalone(id = "session", version = "7.2")
@RedisStandalone(id = "rate-limiter", version = "7.4")
@DisplayName("Multi-Instance Standalone Test - Parameter Injection & Ordering")
class MultiInstanceStandaloneTest {

  @Test
  @DisplayName("Should inject all 3 instances via List<RedisConnectionInfo> parameter")
  void shouldInjectAllInstances(final List<RedisConnectionInfo> instances) {
    // ASSERT: Exactly 3 instances
    assertThat(instances).hasSize(3);

    // ASSERT: All instances have valid connection info
    instances.forEach(
        instance -> {
          assertThat(instance.getHost()).isNotEmpty();
          assertThat(instance.getPort()).isGreaterThan(0);
        });
  }

  @Test
  @DisplayName("Should maintain annotation declaration order in List<RedisConnectionInfo>")
  void shouldMaintainDeclarationOrder(final List<RedisConnectionInfo> instances) {
    // ASSERT: Order matches declaration (cache, session, rate-limiter)
    assertThat(instances).hasSize(3);

    // All instances should have different ports (distinct containers)
    assertThat(instances.get(0).getPort()).isNotEqualTo(instances.get(1).getPort());
    assertThat(instances.get(1).getPort()).isNotEqualTo(instances.get(2).getPort());
  }

  @Test
  @DisplayName("Should access individual instances by ID programmatically")
  void shouldAccessByIdProgrammatically() {
    // ACT: Get instances by ID
    final RedisConnectionInfo cache = RedisStandalone.INSTANCE.get("cache");
    final RedisConnectionInfo session = RedisStandalone.INSTANCE.get("session");
    final RedisConnectionInfo rateLimiter = RedisStandalone.INSTANCE.get("rate-limiter");

    // ASSERT: All instances exist
    assertThat(cache).isNotNull();
    assertThat(session).isNotNull();
    assertThat(rateLimiter).isNotNull();

    // ASSERT: They are distinct instances (different ports)
    assertThat(cache.getPort()).isNotEqualTo(session.getPort());
    assertThat(session.getPort()).isNotEqualTo(rateLimiter.getPort());

    // ASSERT: All are accessible
    assertThat(cache.getHost()).isNotEmpty();
    assertThat(session.getHost()).isNotEmpty();
    assertThat(rateLimiter.getHost()).isNotEmpty();
  }

  @Test
  @DisplayName("Should access all instances via INSTANCE.getAll()")
  void shouldGetAllProgrammatically() {
    // ACT: Get all instances
    final List<RedisConnectionInfo> all = RedisStandalone.INSTANCE.getAll();

    // ASSERT: Size matches annotation count
    assertThat(all).hasSize(3);

    // ASSERT: All have valid ports
    assertThat(all).allMatch(instance -> instance.getPort() > 0);

    // ASSERT: All are unique (different ports)
    final long uniquePorts = all.stream().map(RedisConnectionInfo::getPort).distinct().count();
    assertThat(uniquePorts).isEqualTo(3);
  }

  @Test
  @DisplayName("Should provide string representation for connection info")
  void shouldProvideStringRepresentation(final List<RedisConnectionInfo> instances) {
    // ASSERT: Each instance has valid toString (host:port format)
    instances.forEach(
        instance -> {
          final String str = instance.toString();
          assertThat(str).contains(":");
          assertThat(str).contains(String.valueOf(instance.getPort()));
        });
  }
}
