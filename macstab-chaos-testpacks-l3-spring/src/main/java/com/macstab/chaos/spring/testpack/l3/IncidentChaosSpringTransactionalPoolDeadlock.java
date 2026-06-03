/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.spring.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates the Spring {@code @Transactional(REQUIRES_NEW)} connection pool deadlock:
 * N threads each hold one connection and wait for a second — pool is exhausted — service
 * freezes silently. No database deadlock is visible in DB logs. (Spring #26250)
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: RECV latency at {@code latencyMs} ms, toxicity 1.0 — makes DB connections
 *       slow, accelerating pool exhaustion as threads accumulate in-flight connections
 *   <li>JVM: {@code DataAccessResourceFailureException} injected at METHOD_EXIT on class prefix
 *       {@code classPattern} — reproduces the exception thrown when the connection pool queue
 *       is drained and no connection can be acquired
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>All threads freeze; no DB deadlock log; requires
 * pool exhaustion detection. The failure is invisible until the health endpoint times out.
 *
 * <h2>Industry references</h2>
 * <p>Spring Framework issue #26250 documents REQUIRES_NEW nested transaction patterns that
 * exhaust HikariCP pools, causing a full service hang with no observable database-level
 * deadlock signal.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosSpringTransactionalPoolDeadlock(latencyMs = 3000L)
 * class TransactionalPoolDeadlockTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSpringTransactionalPoolDeadlock.List.class)
@ChaosL3(composer = "com.macstab.chaos.spring.testpack.l3.composers.SpringTransactionalPoolDeadlockComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosSpringTransactionalPoolDeadlock {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Milliseconds of RECV latency to apply to DB connections, accelerating pool exhaustion. */
    long latencyMs() default 3000L;

    /** Class name prefix used to match Spring transaction methods for exception injection. */
    String classPattern() default "org.springframework.transaction";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosSpringTransactionalPoolDeadlock[] value();
    }
}
