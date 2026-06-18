/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a compound Spring Boot startup failure in a Kubernetes environment: DNS resolution
 * races during init container startup, backing services refuse connections, anonymous memory
 * allocations fail under node pressure, and the ApplicationContext itself throws an exception —
 * causing the startup probe to fail and the pod to enter a CrashLoopBackOff cycle.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>DNS: EAI_AGAIN on every forward lookup — service discovery for database, config server, and
 *       messaging brokers fails transiently during the DNS propagation window at pod start
 *   <li>Connection: CONNECT → ECONNREFUSED at toxicity {@code toxicity} — downstream services are
 *       not yet reachable; Spring's {@code @Bean} initialisation fails on first connection
 *   <li>Memory: MMAP_ANON → ENOMEM at probability {@code probability} — node memory pressure causes
 *       anonymous JVM allocations to fail during class loading and context refresh
 *   <li>JVM: ApplicationContextException injected at METHOD_ENTER on class prefix {@code
 *       classPattern} — reproduces the application-level failure observed when the {@code
 *       ApplicationContext} cannot complete its refresh due to infrastructure unavailability
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Startup probe failure loops block the pod from entering the Running state; rolling deploys stall;
 * if all replicas are cycling simultaneously, the service becomes completely unavailable.
 *
 * <h2>Industry references</h2>
 *
 * <p>Kubernetes startup probe failure loops caused by DNS resolution races at pod start are a
 * well-known pattern in microservice deployments. Init container cycles block readiness gates;
 * combined with node memory pressure from over-provisioned pods, startup failures cascade.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.DNS, LibchaosLib.NET, LibchaosLib.MEMORY})
 * @IncidentChaosSpringStartupFailure(toxicity = 0.6, probability = 0.4)
 * class SpringStartupFailureTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSpringStartupFailure.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.springboot.testpack.l3.composers.SpringStartupFailureComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosSpringStartupFailure {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
  double toxicity() default 0.5;

  /** Probability (0.0–1.0) that an anonymous mmap allocation returns ENOMEM. */
  double probability() default 0.3;

  /** Class name prefix used to match Spring context methods for exception injection. */
  String classPattern() default "org.springframework";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosSpringStartupFailure[] value();
  }
}
