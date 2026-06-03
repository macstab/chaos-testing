/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates the Spring Cloud LoadBalancer stale-cache failure: the in-memory instance cache
 * has a default TTL of 30 seconds, meaning dead pod IPs from a rolling deploy remain in
 * rotation for up to 30 seconds. All requests to those IPs fail with ECONNREFUSED.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: CONNECT → ECONNREFUSED at toxicity {@code toxicity} — dead pod IPs
 *       served by the stale load balancer cache
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>MODERATE</strong><br>A 30-second window of ECONNREFUSED errors occurs
 * during every rolling deploy. Well-configured retry logic recovers, but misconfigured
 * clients will surface errors to users.
 *
 * <h2>Industry references</h2>
 * <p>Spring Cloud LoadBalancer default cache TTL is 35 seconds
 * ({@code spring.cloud.loadbalancer.cache.ttl}); stale cache during rolling deploys is a
 * known operational pain point in Spring Boot + Kubernetes deployments.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosFeignStaleLoadBalancer(toxicity = 0.3)
 * class StaleLoadBalancerTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosFeignStaleLoadBalancer.List.class)
@ChaosL3(composer = "com.macstab.chaos.feign.testpack.l3.composers.FeignStaleLoadBalancerComposer", severity = Severity.MODERATE)
public @interface IncidentChaosFeignStaleLoadBalancer {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
    double toxicity() default 0.3;

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosFeignStaleLoadBalancer[] value();
    }
}
