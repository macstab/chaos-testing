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
 * <p>Simulates a Postgres sequence pre-allocation gap after failover: when the primary fails and
 * the replica is promoted, the sequence cache in the old primary is lost and the new primary
 * pre-allocates a fresh block, causing IDs to jump by 32–64. DataIntegrityViolationException is
 * injected at the JDBC connection acquire point (post-failover reconnect path) to surface this at
 * the Java layer.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: DataIntegrityViolationException on class prefix {@code classPattern} at METHOD_EXIT —
 *       injected at the JDBC connection acquire path to reproduce application-level handling of
 *       sequence gaps after failover reconnect
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Pagination breaks due to gap in ID range; unique constraint violations can occur; any code
 * relying on dense-ID assumptions (e.g. sharding by ID modulo) is affected. Reported by incident.io
 * in 2025.
 *
 * <h2>Industry references</h2>
 *
 * <p>Postgres sequence pre-allocation gaps after failover are documented in the Postgres
 * documentation §"Sequence Manipulation Functions". The incident.io 2025 post-mortem describes an
 * ID jump of 32 after a primary failover causing downstream pagination and constraint violations in
 * a production system.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({})
 * @IncidentChaosJdbcSequenceIdJump(classPattern = "org.springframework.jdbc")
 * class JdbcSequenceIdJumpTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJdbcSequenceIdJump.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jdbc.testpack.l3.composers.JdbcSequenceIdJumpComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosJdbcSequenceIdJump {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Class name prefix used to match JDBC connection acquire methods for exception injection. */
  String classPattern() default "org.springframework.jdbc";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJdbcSequenceIdJump[] value();
  }
}
