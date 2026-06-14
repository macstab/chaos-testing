/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates the OkHttp metastable pool failure: slow-server connections survive the idle
 * connection cleanup (keepalive) because they are technically still active, causing the pool to
 * bias toward those slow connections. More requests are routed to the slow pod, which makes it
 * slower, which biases the pool further — a self-sustaining feedback loop.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV latency of {@code latencyMs} ms at 100% toxicity — makes connections slow
 *       but surviving, which the OkHttp pool interprets as valid idle connections
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>SEVERE</strong><br>
 * The slowdown is self-sustaining: slow connections accumulate in the pool, amplifying load on the
 * slow pod, which makes it even slower. Requires manual intervention to break the feedback loop.
 *
 * <h2>Industry references</h2>
 *
 * <p>OkHttp issue #8244; HotOS 2021 paper on metastable failures in distributed systems — pool bias
 * toward slow connections is a canonical example of a metastable failure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosOkHttpMetastablePool(latencyMs = 2000L)
 * class MetastablePoolTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosOkHttpMetastablePool.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.feign.testpack.l3.composers.OkHttpMetastablePoolComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosOkHttpMetastablePool {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** RECV latency in milliseconds injected on all connections (pool bias trigger). */
  long latencyMs() default 2000L;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosOkHttpMetastablePool[] value();
  }
}
