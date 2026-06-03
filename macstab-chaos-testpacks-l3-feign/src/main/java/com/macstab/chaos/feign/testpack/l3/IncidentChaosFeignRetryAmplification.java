/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates retry amplification caused by stacked retry policies: Feign's built-in retry
 * (3 attempts) combined with a Resilience4j retry (3 attempts) multiplies every user request
 * into up to 9 upstream calls during a brownout, saturating downstream services rapidly.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: CONNECT → ECONNREFUSED at toxicity {@code toxicity} — triggers the
 *       brownout condition that activates both retry layers
 *   <li>JVM: IOException injected at METHOD_EXIT on class prefix {@code classPattern} —
 *       forces the Feign retry path to activate alongside Resilience4j retries
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>SEVERE</strong><br>9× upstream amplification during brownout can
 * cascade and take down downstream services that were otherwise healthy.
 *
 * <h2>Industry references</h2>
 * <p>Retry amplification through stacked policies is a well-known anti-pattern in microservice
 * resilience engineering; Feign × Resilience4j stacking is documented in multiple post-mortems.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosFeignRetryAmplification(toxicity = 0.5)
 * class RetryAmplificationTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosFeignRetryAmplification.List.class)
@ChaosL3(composer = "com.macstab.chaos.feign.testpack.l3.composers.FeignRetryAmplificationComposer", severity = Severity.SEVERE)
public @interface IncidentChaosFeignRetryAmplification {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
    double toxicity() default 0.5;

    /** Class name prefix used to match Feign client methods for IOException injection. */
    String classPattern() default "feign";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosFeignRetryAmplification[] value();
    }
}
