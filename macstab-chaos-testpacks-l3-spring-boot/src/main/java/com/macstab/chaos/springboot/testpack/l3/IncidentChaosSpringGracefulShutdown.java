/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a Spring Boot graceful shutdown race under live traffic: SIGTERM is received while
 * active requests are in-flight, the drain window is extended by both network latency and thread
 * join lag, and the Kubernetes grace period expires before all requests complete — causing
 * the pod to be forcibly killed with active connections aborted.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>JVM: AsyncRequestTimeoutException injected at METHOD_ENTER on class prefix
 *       {@code classPattern} — active requests that straddle the shutdown boundary exceed their
 *       async timeout and are terminated with an error response
 *   <li>Connection: RECV latency of {@code drainMs} ms on every receive syscall — in-flight
 *       requests take longer to receive their upstream responses during the drain window,
 *       preventing the servlet container from completing graceful shutdown in time
 *   <li>Process: PTHREAD_CREATE latency of {@code drainMs/2} ms — thread join during container
 *       shutdown is delayed, extending the time the executor pool takes to drain
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Severe</strong><br>Rolling deploy shutdown under live traffic — SIGTERM
 * initiates the drain window but the grace period expires before all active requests complete,
 * leaving clients with reset connections and potentially incomplete idempotent operations.
 *
 * <h2>Industry references</h2>
 * <p>Rolling deploy graceful shutdown races are documented in the Spring Boot lifecycle docs and
 * Kubernetes operator guides. The drain window (spring.lifecycle.timeout-per-shutdown-phase)
 * must exceed the longest request latency; this scenario tests whether the application correctly
 * handles the case where it does not.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.PROCESS})
 * @IncidentChaosSpringGracefulShutdown(drainMs = 15000L)
 * class SpringGracefulShutdownTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSpringGracefulShutdown.List.class)
@ChaosL3(composer = "com.macstab.chaos.springboot.testpack.l3.composers.SpringGracefulShutdownComposer", severity = Severity.SEVERE)
public @interface IncidentChaosSpringGracefulShutdown {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Milliseconds of in-flight request drain time to simulate on RECV and thread joins. */
    long drainMs() default 10000L;

    /** Class name prefix used to match Spring web methods for exception injection. */
    String classPattern() default "org.springframework";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosSpringGracefulShutdown[] value();
    }
}
