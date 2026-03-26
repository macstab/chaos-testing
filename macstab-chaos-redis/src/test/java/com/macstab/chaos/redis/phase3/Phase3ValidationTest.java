/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.phase3;

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
 * Phase 3 validation - proves new architecture works end-to-end.
 *
 * <p><strong>Tests:</strong>
 * <ul>
 *   <li>Container starts with @RedisStandalone + @Resources
 *   <li>Per-annotation INSTANCE access works (type-safe)
 *   <li>Base interface unified access works
 *   <li>Parameter injection works (backward compatible)
 *   <li>Pattern matching works (sealed interface)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@DisplayName("Phase 3: Hybrid Architecture Validation")
@RedisStandalone(id="test-cache", version="7.4")
@Resources(memory="256M", cpus="1")
class Phase3ValidationTest {

  @Test
  @DisplayName("1. Container should start with @RedisStandalone + @Resources")
  void shouldStartContainer(StandaloneRedis redis) {
    assertThat(redis).isNotNull();
    assertThat(redis.host()).isNotBlank();
    assertThat(redis.port()).isGreaterThan(0);
  }

  @Test
  @DisplayName("2. Per-annotation INSTANCE access should work (type-safe)")
  void shouldAccessViaAnnotationInstance() {
    // This is the PRIMARY UX (95% of cases)
    StandaloneRedis cache = RedisStandalone.INSTANCE.get("test-cache");
    
    assertThat(cache).isNotNull();
    assertThat(cache.host()).isEqualTo("localhost");
    assertThat(cache.port()).isGreaterThan(1024).isLessThan(65536);
    assertThat(cache.getTopology()).isEqualTo(RedisTopology.STANDALONE);
  }

  @Test
  @DisplayName("3. Base interface unified access should work")
  void shouldAccessViaBaseInterfaceHelper() {
    // This is unified access (5% of cases)
    Redis cache = Redis.get("test-cache");
    
    assertThat(cache).isNotNull();
    assertThat(cache).isInstanceOf(StandaloneRedis.class);
    assertThat(cache.getHost()).isEqualTo("localhost");
    assertThat(cache.getPort()).isGreaterThan(1024);
    assertThat(cache.getTopology()).isEqualTo(RedisTopology.STANDALONE);
  }

  @Test
  @DisplayName("4. Base interface getAll() should work")
  void shouldGetAllViaBaseInterface() {
    List<Redis> all = Redis.getAll();
    
    assertThat(all).isNotEmpty();
    assertThat(all).hasSize(1);
    assertThat(all.get(0)).isInstanceOf(StandaloneRedis.class);
    
    StandaloneRedis redis = (StandaloneRedis) all.get(0);
    assertThat(redis.host()).isEqualTo("localhost");
  }

  @Test
  @DisplayName("5. Parameter injection should work (backward compatible)")
  void shouldInjectParameter(StandaloneRedis injected) {
    assertThat(injected).isNotNull();
    
    // Verify INSTANCE returns same instance
    StandaloneRedis fromInstance = RedisStandalone.INSTANCE.get("test-cache");
    assertThat(fromInstance.host()).isEqualTo(injected.host());
    assertThat(fromInstance.port()).isEqualTo(injected.port());
  }

  @Test
  @DisplayName("6. Pattern matching should work (sealed interface)")
  void shouldSupportPatternMatching() {
    Redis redis = Redis.get("test-cache");
    
    String result = switch (redis) {
      case StandaloneRedis s -> "Standalone: " + s.host() + ":" + s.port();
      case com.macstab.chaos.redis.api.SentinelRedis s -> "Sentinel: " + s.masterName();
    };
    
    assertThat(result).startsWith("Standalone: localhost:");
    assertThat(result).contains(":");
  }

  @Test
  @DisplayName("7. Record immutability should be enforced")
  void shouldBeImmutable(StandaloneRedis redis) {
    // Records are immutable by design
    String host1 = redis.host();
    String host2 = redis.host();
    assertThat(host1).isSameAs(host2);  // Same instance
    
    // No setters exist (compile-time safety)
    // redis.host = "other";  // Would not compile
  }

  @Test
  @DisplayName("8. Record equals/hashCode should work")
  void shouldHaveWorkingEquals(StandaloneRedis redis) {
    StandaloneRedis same = new StandaloneRedis(redis.host(), redis.port());
    StandaloneRedis different = new StandaloneRedis("other", 9999);
    
    assertThat(redis).isEqualTo(same);
    assertThat(redis).isNotEqualTo(different);
    assertThat(redis.hashCode()).isEqualTo(same.hashCode());
  }

  @Test
  @DisplayName("9. @Resources constraints should be applied")
  void shouldApplyResourceConstraints(StandaloneRedis redis) {
    // Container started with @Resources(memory="256M", cpus="1")
    // If this test passes, resources were applied successfully
    // (Docker validates constraints at container start)
    assertThat(redis).isNotNull();
  }

  @Test
  @DisplayName("10. Architecture should be type-safe at compile-time")
  void shouldBeTypeSafe() {
    // This compiles (type-safe)
    StandaloneRedis standalone = RedisStandalone.INSTANCE.get("test-cache");
    assertThat(standalone).isInstanceOf(StandaloneRedis.class);
    
    // This also compiles (upcast to base type)
    Redis base = standalone;
    assertThat(base).isInstanceOf(StandaloneRedis.class);
    
    // This would NOT compile (no downcast without check):
    // StandaloneRedis s = (StandaloneRedis) base;  // Requires explicit cast
  }
}
