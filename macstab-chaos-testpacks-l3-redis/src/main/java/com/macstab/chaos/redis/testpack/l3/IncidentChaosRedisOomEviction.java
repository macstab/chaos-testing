/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates the compound failure when Redis hits its {@code maxmemory} limit: aggressive
 * eviction causes clients to receive connection resets, while the application JVM and OS both
 * encounter memory pressure from defensive buffering and retry allocation.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Memory: MMAP_ANON → ENOMEM at probability {@code probability} — anonymous memory
 *       allocations fail, reproducing the host-side OOM that accompanies Redis eviction pressure
 *   <li>Connection: CONNECT → ECONNRESET at toxicity {@code toxicity} — Redis forcibly closes
 *       connections during eviction storms to shed load
 *   <li>JVM: OutOfMemoryError injected on methods matching class prefix {@code "redis"} — simulates
 *       the Java heap impact of defensive copy-on-eviction read patterns
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Combined memory pressure and connection instability trigger cascading client failures;
 * applications relying on Redis as a session store or rate-limiter lose state atomicity during the
 * eviction window.
 *
 * <h2>Industry references</h2>
 *
 * <p>Redis maxmemory eviction storms are documented in Redis documentation (maxmemory-policy) and
 * operator post-mortems, particularly in deployments where client-side buffering amplifies host
 * memory pressure once Redis starts returning -OOM errors.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.MEMORY, LibchaosLib.NET})
 * @IncidentChaosRedisOomEviction(toxicity = 0.7, probability = 0.8)
 * class RedisOomEvictionTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosRedisOomEviction.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.redis.testpack.l3.composers.RedisOomEvictionComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosRedisOomEviction {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of CONNECT syscalls that return ECONNRESET (0.0–1.0). */
  double toxicity() default 0.6;

  /** Probability (0.0–1.0) that an anonymous mmap allocation returns ENOMEM. */
  double probability() default 0.7;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosRedisOomEviction[] value();
  }
}
