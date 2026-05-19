/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * L1 chaos primitive: load a hand-written JVM-agent plan JSON file (from the test classpath) and
 * push it into the target container via {@code CompositeJavaChaos.applyPlan}.
 *
 * <p><strong>Why this annotation exists right now.</strong> The chaos JVM agent's typed plan API
 * ({@code com.macstab.chaos.jvm.api.ChaosPlan} / {@code ChaosScenario} / {@code ChaosEffect})
 * lives in the {@code chaos-agent-api} project — separate from this repository. Until those
 * typed classes are reachable here, the L1 tier for JVM cannot generate the 60+ effect-specific
 * annotations the design calls for. This single annotation is the escape-hatch that bridges the
 * L1 lifecycle (apply on {@code beforeAll}/{@code beforeEach}, clear on
 * {@code afterEach}/{@code afterAll}) to the agent's wire format <em>today</em>, without
 * pretending to know the JSON schema.
 *
 * <p><strong>Follow-on.</strong> Once chaos-agent-api is reachable from this repo, the L1 tier
 * will grow per-effect annotations following the same selector × effect pattern proven for
 * libchaos modules ({@code @ChaosJdbcDelay}, {@code @ChaosHeapPressure}, etc.). This annotation
 * stays as the escape-hatch for plans not yet covered by the typed L1 surface.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @JvmAgentChaos
 * @ChaosJvmPlan(planJsonResource = "/chaos-plans/jdbc-latency.json")
 * class MyTest {
 *
 *   @Test
 *   void appHandlesJdbcLatency(RedisConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p>The resource must be a classpath path readable by {@code
 * ClassLoader.getResourceAsStream(planJsonResource())}. Its contents are pushed verbatim to the
 * agent — the schema is the agent project's concern, not this library's.
 *
 * <p><strong>Cleanup:</strong> on {@code afterEach} (method-scope) or {@code afterAll}
 * (class-scope), the framework calls {@code CompositeJavaChaos.clearPlan} to reset the agent
 * to an empty plan ({@code {"scenarios":[]}}). Per-rule cleanup isn't possible because the
 * agent's wire model is wholesale plan replacement, not per-rule add/remove.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.CompositeJavaChaos#applyPlan
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.JvmPlanTranslator")
public @interface ChaosJvmPlan {

  /**
   * @return classpath-relative path to the plan JSON file (e.g. {@code "/chaos-plans/foo.json"})
   */
  String planJsonResource();

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the JVM agent is not active on the container ({@code ERROR} fails at
   *     {@code beforeAll}; {@code ABORT} marks the test class YELLOW/aborted)
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
