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
 * <p>Simulates a Spring Cloud Config Server outage: DNS hard-fails for all forward lookups,
 * connection attempts to the config server time out, and the application JVM receives a
 * ConnectException during the config refresh cycle — causing the service to continue with stale
 * configuration, potentially diverging from the expected feature flag state.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>DNS: EAI_FAIL on every forward lookup — the config server hostname cannot be resolved;
 *       Spring Cloud Config client cannot establish a connection to retrieve property sources
 *   <li>Connection: timeout at {@code timeoutMs} ms at toxicity {@code toxicity} — connection
 *       attempts to the config server port hang until the configured timeout expires; refresh
 *       cycles block application threads during the wait
 *   <li>JVM: ConnectException injected at METHOD_ENTER on class prefix {@code classPattern} —
 *       reproduces the exception raised by the config client when the server is unreachable,
 *       exercising fail-fast vs. cached-config fallback logic
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Config Server down → scheduled refresh fails → stale config persists → feature flag divergence
 * between pods; if fail-fast is enabled and the application cannot start without fresh config, a
 * CrashLoopBackOff ensues.
 *
 * <h2>Industry references</h2>
 *
 * <p>Spring Cloud Config Server availability is a single point of failure for config refresh in
 * multi-service deployments. Post-mortems document feature flag divergence and blue/green split
 * traffic when some pods receive stale configs while others successfully refresh.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.DNS, LibchaosLib.NET})
 * @IncidentChaosSpringConfigServerDown(toxicity = 0.9, timeoutMs = 3000L)
 * class SpringConfigServerDownTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSpringConfigServerDown.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.springboot.testpack.l3.composers.SpringConfigServerDownComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosSpringConfigServerDown {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of connection attempts to the config server that time out (0.0–1.0). */
  double toxicity() default 0.8;

  /** Milliseconds before a connection attempt to the config server times out. */
  long timeoutMs() default 5000L;

  /** Class name prefix used to match Spring Cloud config client methods for exception injection. */
  String classPattern() default "org.springframework.cloud";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosSpringConfigServerDown[] value();
  }
}
