/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.spring.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a Spring Cloud Config simultaneous {@code /refresh} wave: 10+ nodes trigger
 * bean destruction concurrently — config server DNS lookup fails — refresh hangs — cascading
 * timeout across the fleet. (Spring Cloud Config #2341)
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: RECV latency at {@code latencyMs} ms, toxicity 1.0 — config server
 *       responses are slow, causing refresh cycles to hang and accumulate
 *   <li>DNS: EAI_AGAIN on all forward lookups — the config server hostname cannot be resolved
 *       during the refresh wave, making the hang indefinite
 *   <li>JVM: {@code BeanCreationException} injected at METHOD_EXIT on class prefix
 *       {@code classPattern} — reproduces the failure raised when a bean cannot be created
 *       during the destructive refresh cycle
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Severe</strong><br>Every refreshing pod drops in-flight requests;
 * cascades if the config server is slow because all nodes enter refresh simultaneously,
 * causing a fleet-wide traffic drop.
 *
 * <h2>Industry references</h2>
 * <p>Spring Cloud Config issue #2341 documents the cascading failure when multiple pods
 * receive a simultaneous refresh trigger (e.g. from a config change push), causing a
 * synchronized bean destruction wave that drops traffic across the entire service fleet.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.DNS, LibchaosLib.NET})
 * @IncidentChaosSpringConfigRefreshWave(latencyMs = 1000L)
 * class ConfigRefreshWaveTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSpringConfigRefreshWave.List.class)
@ChaosL3(composer = "com.macstab.chaos.spring.testpack.l3.composers.SpringConfigRefreshWaveComposer", severity = Severity.SEVERE)
public @interface IncidentChaosSpringConfigRefreshWave {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Milliseconds of RECV latency to apply, making config server responses slow. */
    long latencyMs() default 1000L;

    /** Class name prefix used to match Spring Cloud context methods for exception injection. */
    String classPattern() default "org.springframework.cloud.context";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosSpringConfigRefreshWave[] value();
    }
}
