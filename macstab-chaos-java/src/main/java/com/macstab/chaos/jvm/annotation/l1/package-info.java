/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for the JVM agent — currently a single escape-hatch annotation
 * ({@link com.macstab.chaos.jvm.annotation.l1.ChaosJvmPlan}) that loads a hand-written plan JSON
 * file and pushes it through the L1 lifecycle.
 *
 * <p><strong>Why only one annotation right now:</strong> the agent's typed plan API
 * ({@code com.macstab.chaos.jvm.api.ChaosPlan} / {@code ChaosScenario} / {@code ChaosEffect})
 * lives in the {@code chaos-agent-api} project external to this repository. Until those typed
 * classes are reachable here, the full effect-specific L1 surface (one annotation per
 * interceptor / stressor — e.g. {@code @ChaosJdbcDelay}, {@code @ChaosHeapPressure}) cannot be
 * generated without guessing at JSON schemas. The current annotation lets users push plans
 * they've written by hand against the agent's documented wire format.
 *
 * <p><strong>Roadmap:</strong> once chaos-agent-api is wired in, this package grows the typed L1
 * surface following the same selector-x-effect pattern proven for libchaos modules (memory,
 * process, time, dns, connection, filesystem). {@link com.macstab.chaos.jvm.annotation.l1.ChaosJvmPlan}
 * stays as the escape-hatch for plans not yet covered by the typed surface.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.jvm.annotation.l1;
