/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.jvm_runtime;

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
 * Delays every {@code Method.invoke()} call by a configurable number of milliseconds, simulating a
 * slow reflective dispatch path and exposing timeout assumptions in frameworks that use reflection
 * heavily.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code REFLECTION_INVOKE} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code java.lang.reflect.Method.invoke()} in the
 *       target container's JVM.
 *   <li>Before forwarding the invocation to the real method, the interceptor parks the calling
 *       thread for a duration sampled uniformly between {@link #delayMs()} and {@link
 *       #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real {@code Method.invoke()} executes and the result is returned
 *       normally to the caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Dependency injection startup slow.</strong> Spring, CDI, and Guice use {@code
 *       Method.invoke()} extensively during context startup (post-construct callbacks, factory
 *       methods, event listeners); assert that context initialisation completes within the pod
 *       readiness probe timeout.
 *   <li><strong>Serialisation frameworks time out.</strong> Jackson, Gson, and similar libraries
 *       use reflection to access fields and getters; assert that serialisation operations time out
 *       with a meaningful error rather than blocking a thread indefinitely.
 *   <li><strong>Dynamic proxy chains amplified.</strong> In proxy-heavy architectures (AOP,
 *       interceptors, remote proxies) each proxy layer issues a reflective invoke; with a 100 ms
 *       delay per invoke, a chain of five proxies adds 500 ms of latency. Assert that the
 *       application's end-to-end timeout budget accommodates this.
 *   <li><strong>Production failure mode:</strong> test frameworks and plugin architectures that
 *       dispatch event listeners via reflection can saturate their thread pools when each dispatch
 *       is slow, causing queued events to pile up and eventually being dropped.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code Method.invoke()} is implemented in Java as a delegation to a dynamically generated
 * accessor stub (the "inflation" mechanism), or, before inflation, as a native call via {@code
 * sun.reflect.NativeMethodAccessorImpl}. The agent intercepts at the {@code
 * java.lang.reflect.Method.invoke(Object, Object...)} level — the public API — so all reflective
 * invocations regardless of accessor type are delayed.
 *
 * <p>The delay fires on the thread that issues the {@code Method.invoke()} call. Because the
 * underlying method has not yet been called, the delay adds to the total latency of the operation,
 * not to any internal timeout inside the reflected method. This distinction matters for frameworks
 * that maintain their own per-operation timeout budgets: the injected delay consumes budget before
 * the actual work even starts.
 *
 * <p>This annotation is particularly useful for testing Spring Boot applications, where {@code
 * Method.invoke()} appears in request mapping dispatch, {@code @Scheduled} task invocation,
 * {@code @EventListener} dispatch, and {@code @Async} method proxying. Each of these code paths
 * runs on a different thread pool, so a flat delay per invoke can expose which pool is most
 * sensitive to slow reflective dispatch.
 *
 * <p>The delay does not wrap the full method body — only the hand-off from the caller to {@code
 * Method.invoke()}. If the reflected method itself blocks, the total wall time will be delay +
 * method execution time. Tests should be designed accordingly and not assume that the delay alone
 * determines latency.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosReflectionInvokeDelay(delayMs = 200, maxDelayMs = 500)
 * class ReflectionDelayTest {
 *   @Test
 *   void springContextStartsWithinProbeTimeoutUnderReflectionDelay(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an {@code
 *       ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>Chaos agent JAR</strong> accessible at the path configured in
 *       {@code @JvmAgentChaos}.
 *   <li><strong>{@code macstab-chaos-java} on the test classpath</strong> — required for the
 *       translator.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosReflectionInvokeDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.REFLECTION_INVOKE)
public @interface ChaosReflectionInvokeDelay {

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
   * @ChaosReflectionInvokeDelay(id = "primary",  probability = 0.001)
   * @ChaosReflectionInvokeDelay(id = "replica",  probability = 0.01)
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
    ChaosReflectionInvokeDelay[] value();
  }
}
