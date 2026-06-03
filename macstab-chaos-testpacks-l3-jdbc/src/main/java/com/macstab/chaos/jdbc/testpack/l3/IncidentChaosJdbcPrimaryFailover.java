/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a JDBC primary-failover event: the primary database goes down, replica promotion
 * is in progress, and DNS-based failover causes a transient name-resolution flap while connections
 * are still being refused by the new primary that has not yet fully taken over.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: CONNECT → ECONNREFUSED at {@code toxicity} — new connections to the database
 *       are refused while the replica is being promoted
 *   <li>DNS: EAI_AGAIN on every forward lookup — transient DNS flap during IP rebinding after
 *       replica promotion
 *   <li>JVM: {@code injectException("java.sql.SQLTransientConnectionException", "primary failover
 *       in progress")} on classes matching {@code classPattern} at METHOD_ENTER — surfaces
 *       failover state at the Java layer
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>All clients lose connectivity for the duration of the
 * promotion window; retry storms against the DNS resolver and connection pool are common side
 * effects, extending the unavailability window beyond the actual failover time.
 *
 * <h2>Industry references</h2>
 * <p>Primary-down, replica-promotion lag is a classic PostgreSQL and MySQL HA failure mode:
 * documented in AWS RDS Multi-AZ failover documentation, the Percona blog "MySQL Failover
 * Benchmarks", and numerous post-mortems describing DNS-based failover causing transient blips.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
 * @IncidentChaosJdbcPrimaryFailover(toxicity = 0.9)
 * class JdbcPrimaryFailoverTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJdbcPrimaryFailover.List.class)
@ChaosL3(composer = "com.macstab.chaos.jdbc.testpack.l3.composers.JdbcPrimaryFailoverComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosJdbcPrimaryFailover {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
    double toxicity() default 0.8;

    /** Class name prefix used to match JDBC client methods for exception injection. */
    String classPattern() default "jdbc";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosJdbcPrimaryFailover[] value();
    }
}
