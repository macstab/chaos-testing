/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.nio;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Delay the nio_channel_read operation by the configured number of milliseconds.
 *
 * <p><strong>What this annotation is:</strong> a JVM agent L1 chaos primitive — one typed
 * annotation per (selector family, operation type, effect) tuple. It is declared on the test class
 * alongside a container annotation and activates for the lifetime of the test class (class-scope)
 * or a single {@code @Test} method (method-scope).
 *
 * <p><strong>What chaos this applies:</strong> delay the NIO_CHANNEL_READ operation by the
 * configured number of milliseconds inside the JVM of the target container. The effect fires on
 * every matching call, subject to the probability configured via {@link #probability()} if
 * applicable. The rule is active from {@code beforeAll} until {@code afterAll} (class-scope) or
 * from {@code beforeEach} until {@code afterEach} (method-scope).
 *
 * <p><strong>How this occurs (mechanism):</strong> the {@code @JvmAgentChaos} annotation on the
 * container declaration causes {@code ChaosTestingExtension} to attach the chaos Java agent to the
 * container's JVM before it starts (via {@code -javaagent}). The agent uses Byte Buddy to install
 * method interceptors at runtime. This annotation adds a typed {@code ChaosScenario} to the
 * container's active {@code ChaosPlan} via {@link
 * com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator}; the accumulator serialises the merged
 * plan and pushes it to the agent API after every change.
 *
 * <p><strong>What is required:</strong>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation (e.g.
 *       {@code @AppContainer}) — this attaches the chaos agent to the container JVM before it
 *       starts; omitting it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be accessible at the path configured in
 *       {@code @JvmAgentChaos}; the agent is attached before container start.
 *   <li><strong>{@code macstab-chaos-java} on the test classpath</strong> — without it the
 *       translator class cannot be loaded.
 *   <li><strong>Java container image</strong> — the target container must run a JVM process; the
 *       agent cannot intercept native executables.
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioChannelReadDelay
 * class JvmChaosTest {
 *   @Test
 *   void appHandlesFault(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container; the default empty
 * string applies to every agent-capable container. Use the repeatable form
 * ({@code @ChaosNioChannelReadDelays}) to apply different configurations to different containers.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosNioChannelReadDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_CHANNEL_READ)
public @interface ChaosNioChannelReadDelay {

  /**
   * @return min delay in milliseconds
   */
  long delayMs() default 100L;

  /**
   * @return max delay in milliseconds (defaults to delayMs for deterministic delay)
   */
  long maxDelayMs() default 100L;

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
   *
   * <pre>{@code
   * @ChaosNioChannelReadDelay(id = "primary",  probability = 0.001)
   * @ChaosNioChannelReadDelay(id = "replica",  probability = 0.01)
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
    ChaosNioChannelReadDelay[] value();
  }
}
