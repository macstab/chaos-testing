/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.spring.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates Spring WebFlux reactor thread starvation: a blocking call on a reactor carrier
 * thread monopolizes all 2×CPU reactor threads — the health endpoint times out — pod is killed by
 * the orchestrator. (JDriven post-mortem)
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV latency at {@code latencyMs} ms, toxicity 1.0 — every downstream I/O call
 *       blocks the reactor thread for the full latency duration, monopolizing carriers
 *   <li>JVM: {@code reactor.core.publisher.Operators$OnNextFailedException} injected at METHOD_EXIT
 *       on class prefix {@code classPattern} — reproduces the error propagated when a reactor
 *       operator drops an element due to a blocked pipeline
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Health endpoint times out → pod killed; no queue overflow is visible because reactor threads are
 * monopolized, not queued.
 *
 * <h2>Industry references</h2>
 *
 * <p>JDriven post-mortem on blocking calls inside WebFlux reactive pipelines: all reactor carrier
 * threads (2×CPU) become blocked on a single synchronous dependency, starving the event loop and
 * causing liveness probe failure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosSpringWebFluxReactorStarvation(latencyMs = 5000L)
 * class ReactorStarvationTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosSpringWebFluxReactorStarvation.List.class)
@ChaosL3(
    composer =
        "com.macstab.chaos.spring.testpack.l3.composers.SpringWebFluxReactorStarvationComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosSpringWebFluxReactorStarvation {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Milliseconds of RECV latency to apply, blocking reactor threads on downstream I/O. */
  long latencyMs() default 5000L;

  /** Class name prefix used to match reactor core methods for exception injection. */
  String classPattern() default "reactor.core";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosSpringWebFluxReactorStarvation[] value();
  }
}
