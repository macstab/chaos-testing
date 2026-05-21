/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.stressors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Activates a self-driving JVM stressor that exhaust direct buffer memory (off-heap NIO) inside the target container's JVM for the
 * duration of the test class or test method.
 *
 * <p><strong>What this annotation is:</strong> a JVM agent stressor L1 primitive. Unlike
 * interceptor primitives, stressors don't intercept a specific JVM operation — they spawn a
 * self-driving background routine that runs from activation ({@code beforeAll} or
 * {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 *
 * <p><strong>What chaos this applies:</strong> the stressor exhaust direct buffer memory (off-heap NIO). The effect persists
 * throughout the test and is not probabilistic — it runs continuously at the configured intensity
 * until the rule is removed.
 *
 * <p><strong>How this occurs (mechanism):</strong> the {@code @JvmAgentChaos} annotation on the container declaration causes {@code ChaosTestingExtension} to attach the chaos Java agent to the container's JVM before it starts (via {@code -javaagent}). The agent uses Byte Buddy to install method interceptors at runtime. This annotation adds a typed {@code ChaosScenario} to the container's active {@code ChaosPlan} via {@link com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator}; the accumulator serialises the merged plan and pushes it to the agent API after every change.
 *
 * <p><strong>What is required:</strong>
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation (e.g.
 *       {@code @AppContainer}) — attaches the chaos agent before container start.</li>
 *   <li><strong>The chaos agent JAR</strong> accessible at the configured path.</li>
 *   <li><strong>{@code macstab-chaos-java} on the test classpath.</strong></li>
 *   <li><strong>Java container image</strong> — the target must run a JVM process.</li>
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosDirectBufferPressure
 * class JvmStressorTest {
 *   @Test
 *   void appResilientUnderStress(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Scope:</strong> {@link #id()} binds to a single container; the default empty string
 * applies to every agent-capable container. Use the repeatable form ({@code @ChaosDirectBufferPressures}) to
 * apply different stressor intensities to different containers.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosDirectBufferPressure.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.DirectBufferPressureTranslator")
public @interface ChaosDirectBufferPressure {

  /**
   * @return total bytes to allocate off-heap (> 0)
   */
  long totalBytes() default 268_435_456L;

  /**
   * @return per-buffer size in bytes (> 0)
   */
  int bufferSizeBytes() default 1_048_576;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the JVM agent is not active on the container
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   * <pre>{@code
   * @ChaosDirectBufferPressure(id = "primary",  probability = 0.001)
   * @ChaosDirectBufferPressure(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosDirectBufferPressure[] value();
  }
}
