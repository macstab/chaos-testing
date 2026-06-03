/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.http.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a retry amplification storm: connection-refused errors on a fraction of requests
 * cause HTTP clients to retry, multiplying the effective load on the already-failing upstream
 * until the full request fan-out saturates every available connection.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: CONNECT → ECONNREFUSED at toxicity {@code toxicity} — initial failures that
 *       trigger client retry logic
 *   <li>JVM: IOException injected at METHOD_ENTER on class prefix {@code classPattern} —
 *       forces the client library's retry path to activate, amplifying the load multiplier
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>A 50% initial error rate becomes 100% saturation
 * once retries are factored in; with exponential backoff disabled or misconfigured the upstream
 * is destroyed within seconds of the first failure.
 *
 * <h2>Industry references</h2>
 * <p>Retry storms turning partial failures into full outages are described in Amazon's "Exponential
 * Backoff and Jitter" blog post and Google's SRE book chapter on cascading failures — retries
 * without jitter are a primary amplification mechanism in microservice outages.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosHttpRetryAmplification(toxicity = 0.5)
 * class RetryAmplificationTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosHttpRetryAmplification.List.class)
@ChaosL3(composer = "com.macstab.chaos.http.testpack.l3.composers.HttpRetryAmplificationComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosHttpRetryAmplification {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
    double toxicity() default 0.5;

    /** Class name prefix used to match HTTP client methods for IOException injection. */
    String classPattern() default "http";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosHttpRetryAmplification[] value();
    }
}
