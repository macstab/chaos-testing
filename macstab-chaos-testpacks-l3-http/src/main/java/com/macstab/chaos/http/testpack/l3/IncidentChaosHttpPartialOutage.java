/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.http.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a partial backend outage where a fraction of instances are unreachable while
 * others continue to serve traffic. DNS resolution transiently fails for affected pods, and
 * connection attempts time out, producing a mixed healthy/failing response profile that
 * exposes load-balancer and circuit-breaker logic to realistic split conditions.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: CONNECT → ETIMEDOUT at toxicity {@code toxicity} — connection attempts to
 *       the affected fraction of backends time out without reaching the server
 *   <li>DNS: EAI_AGAIN on every forward lookup — transient DNS resolution failures simulate
 *       pod-level endpoint deregistration races during the partial outage window
 *   <li>JVM: ConnectException injected at METHOD_ENTER on class prefix {@code classPattern} —
 *       ensures the application's exception handling for mixed-success scenarios is exercised
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Severe</strong><br>A 50% error rate under mixed healthy/failing backends
 * is often harder to detect and route around than a full outage; circuit breakers tuned for
 * full-failure may not open, allowing sustained error injection into user traffic.
 *
 * <h2>Industry references</h2>
 * <p>Partial outages with mixed healthy/failing backends are a common source of subtle production
 * incidents documented in Stripe and GitHub post-mortems — the mixed signal confuses monitoring
 * thresholds and delays incident detection compared to clean full-outage events.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
 * @IncidentChaosHttpPartialOutage(toxicity = 0.5)
 * class PartialOutageTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosHttpPartialOutage.List.class)
@ChaosL3(composer = "com.macstab.chaos.http.testpack.l3.composers.HttpPartialOutageComposer", severity = Severity.SEVERE)
public @interface IncidentChaosHttpPartialOutage {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls that return ETIMEDOUT (0.0–1.0). */
    double toxicity() default 0.5;

    /** Class name prefix used to match HTTP client methods for ConnectException injection. */
    String classPattern() default "http";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosHttpPartialOutage[] value();
    }
}
