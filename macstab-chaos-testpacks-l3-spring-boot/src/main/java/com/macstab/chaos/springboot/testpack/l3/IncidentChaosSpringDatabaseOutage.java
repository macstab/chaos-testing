/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a complete database outage as seen by a Spring Boot application: the database host
 * refuses new connections, DNS resolution of the database hostname fails transiently, and the
 * application JVM receives a DataAccessResourceFailureException — the expected trigger for
 * Resilience4j circuit breaker opening and readiness probe failure.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: CONNECT → ECONNREFUSED at toxicity {@code toxicity} — database port not
 *       accepting; all JDBC connection pool acquisition attempts fail immediately; pool
 *       exhaustion follows as borrowing threads wait for a connection that never arrives
 *   <li>DNS: EAI_AGAIN on every forward lookup — database hostname resolution fails transiently;
 *       connection pool validation checks cannot resolve the host to re-establish connections
 *   <li>JVM: DataAccessResourceFailureException injected at METHOD_ENTER on class prefix
 *       {@code classPattern} — reproduces the Spring Data exception raised when the data source
 *       is unreachable, exercising the circuit breaker and readiness indicator logic
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>DB gone → Resilience4j circuit breaker opens →
 * readiness probe fails → pod removed from load balancer rotation; if the database recovers
 * before the circuit half-opens, backlog of queued requests is served in a burst.
 *
 * <h2>Industry references</h2>
 * <p>Database outage → circuit breaker pattern → readiness probe failure is the canonical
 * Resilience4j + Spring Boot Actuator failure scenario, documented in the Resilience4j Spring
 * Boot starter guide and Spring Boot Actuator health indicator documentation.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
 * @IncidentChaosSpringDatabaseOutage(toxicity = 0.95)
 * class SpringDatabaseOutageTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSpringDatabaseOutage.List.class)
@ChaosL3(composer = "com.macstab.chaos.springboot.testpack.l3.composers.SpringDatabaseOutageComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosSpringDatabaseOutage {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
    double toxicity() default 0.9;

    /** Class name prefix used to match Spring Data repository methods for exception injection. */
    String classPattern() default "org.springframework";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosSpringDatabaseOutage[] value();
    }
}
