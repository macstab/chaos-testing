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
 * <p>Simulates a Redis slow-log command backlog: individual commands take longer than the {@code
 * slowlog-log-slower-than} threshold, causing the server's single-threaded event loop to queue up
 * subsequent commands and eventually time out callers.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV latency of {@code latencyMs} ms on every receive syscall — slows response
 *       delivery to the client, mirroring slow command execution from the client's view
 *   <li>JVM: SocketTimeoutException injected at METHOD_ENTER on methods matching {@code
 *       classPattern} — causes Redis client method calls to fail with a timeout before data
 *       arrives, reproducing the application-level symptom of slow-log backlog
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Command backlog cascades into connection-pool saturation and increased latency percentiles; in
 * throughput-sensitive paths it degrades to full unavailability as threads block waiting for
 * responses.
 *
 * <h2>Industry references</h2>
 *
 * <p>Redis slow-log command backlog cascade is a classic single-threaded Redis failure mode,
 * documented in the Redis SLOWLOG documentation and observable whenever KEYS, SORT, or large LRANGE
 * commands are mixed with latency-sensitive request paths.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosRedisSlowlog(latencyMs = 800L)
 * class RedisSlowlogTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosRedisSlowlog.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.redis.testpack.l3.composers.RedisSlowlogComposer",
    severity = Severity.MODERATE)
public @interface IncidentChaosRedisSlowlog {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Milliseconds of latency injected into each Redis RECV syscall. */
  long latencyMs() default 500L;

  /** Class name prefix used to match Redis client methods for timeout injection. */
  String classPattern() default "redis";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosRedisSlowlog[] value();
  }
}
