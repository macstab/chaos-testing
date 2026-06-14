/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.spring.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates Spring Open Session In View (OSIV) connection starvation: with OSIV default ON, a DB
 * connection is held open for the entire HTTP request lifecycle including JSON serialization. At a
 * traffic spike the connection pool is drained. Default Spring Boot configuration triggers this —
 * often mistaken for database slowness.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV latency at {@code latencyMs} ms, toxicity 1.0 — extends the lifespan of
 *       each OSIV-held connection, accelerating pool drain under load
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Default Spring Boot config triggers this at traffic spikes; often mistaken for DB slowness
 * because the latency is real but the root cause is the OSIV anti-pattern, not database
 * performance.
 *
 * <h2>Industry references</h2>
 *
 * <p>Spring Boot enables OSIV by default ({@code spring.jpa.open-in-view=true}), a well-known
 * production hazard documented in the Spring Data team's own migration guides. Teams discover the
 * problem only at scale, when connection pool exhaustion appears as generic 500 errors.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosSpringOsivConnectionStarvation(latencyMs = 2000L)
 * class OsivConnectionStarvationTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSpringOsivConnectionStarvation.List.class)
@ChaosL3(
    composer =
        "com.macstab.chaos.spring.testpack.l3.composers.SpringOsivConnectionStarvationComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosSpringOsivConnectionStarvation {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Milliseconds of RECV latency to apply, extending OSIV connection lifespan. */
  long latencyMs() default 2000L;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosSpringOsivConnectionStarvation[] value();
  }
}
