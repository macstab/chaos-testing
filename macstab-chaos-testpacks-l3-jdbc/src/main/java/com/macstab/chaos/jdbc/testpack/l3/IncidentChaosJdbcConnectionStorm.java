/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a JDBC connection pool exhaustion storm: a load spike causes every
 * connection-acquire attempt to be refused at the network level while the JVM layer simultaneously
 * reports pool exhaustion, driving request queues to saturation.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: CONNECT → ECONNREFUSED at {@code toxicity} — rejects new TCP connections to the
 *       database, preventing the pool from opening replacement connections
 *   <li>JVM: {@code injectException("java.sql.SQLException", "connection pool exhausted")} on
 *       classes matching {@code classPattern} at METHOD_ENTER — surfaces pool exhaustion at the
 *       Java layer before the socket layer even attempts to connect
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Connection-acquire blocks, pool queue fills, and every inbound request stacks up waiting for a
 * connection that will never arrive; the service degrades to complete unavailability within
 * seconds.
 *
 * <h2>Industry references</h2>
 *
 * <p>JDBC pool exhaustion under load spikes is a well-documented failure mode: described in
 * HikariCP documentation §"Pool sizing", the Percona blog "Diagnosing Connection Pool Exhaustion",
 * and numerous post-mortems from high-traffic e-commerce deployments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosJdbcConnectionStorm(toxicity = 0.9, classPattern = "com.example.repo")
 * class JdbcConnectionStormTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJdbcConnectionStorm.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jdbc.testpack.l3.composers.JdbcConnectionStormComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosJdbcConnectionStorm {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
  double toxicity() default 0.7;

  /** Class name prefix used to match JDBC client methods for exception injection. */
  String classPattern() default "jdbc";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJdbcConnectionStorm[] value();
  }
}
