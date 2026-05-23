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
 * Delays every {@code MBeanServer.getAttribute()} call by a configurable number of milliseconds,
 * simulating a slow or contended JMX attribute read.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code JMX_GET_ATTR} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to
 *       {@code javax.management.MBeanServer.getAttribute(ObjectName, String)} in the target
 *       container's JVM.
 *   <li>Before forwarding the call to the MBean implementation, the interceptor parks the calling
 *       thread for a duration sampled uniformly between {@link #delayMs()} and
 *       {@link #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real attribute read executes and the value is returned to the caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Monitoring scrape times out.</strong> Prometheus JMX exporters, Jolokia, or custom
 *       management probes that read MBean attributes will time out if the delay exceeds the scrape
 *       interval; assert that the monitoring system marks the target as stale rather than raising
 *       a false alert.
 *   <li><strong>Auto-scaling decisions delayed.</strong> Auto-scalers that read JVM metrics (heap
 *       usage, thread count) via JMX to decide on scaling actions will operate on stale data
 *       during the delay window; assert that the auto-scaler handles stale or missing data safely.
 *   <li><strong>Health-check probe slow.</strong> JVM health checks implemented via JMX attribute
 *       reads will be slow to respond; assert that the orchestration layer distinguishes between
 *       a slow health check and a failed one.
 *   <li><strong>Production failure mode:</strong> JMX attribute reads that invoke synchronised
 *       application code (e.g. reading a connection pool's active count via the pool's own lock)
 *       can block application threads if the JMX read delays cause the JMX client to issue
 *       concurrent retries that accumulate, all waiting for the same lock.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code MBeanServer.getAttribute()} is implemented in HotSpot's JMX layer as a call to the
 * registered {@code DynamicMBean} or the reflection-based accessor for standard MBeans. The agent
 * intercepts at the {@code MBeanServer} interface level, so the delay fires before any MBean-side
 * code runs. This means the delay is additive with any latency introduced by the MBean's own
 * attribute implementation (e.g. a getter that computes a value under a lock).
 *
 * <p>JMX calls can originate from multiple sources: remote JMX clients over RMI/JMXMP, local
 * management tools such as JConsole or VisualVM, internal monitoring threads, and framework
 * introspection code. The delay applies to all of these uniformly, so tests that issue JMX calls
 * from the test side (via {@code JMXConnector}) will also be affected; the test must account for
 * the delay in its assertion timeouts.
 *
 * <p>Combining this annotation with {@link ChaosJmxGetAttrInjectException} in a repeatable form
 * lets a test first experience slow attribute reads (simulating a loaded MBean) and then hard
 * failures (simulating a broken MBean registration), covering both degradation paths.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJmxGetAttrDelay(delayMs = 500, maxDelayMs = 2_000)
 * class JmxAttrDelayTest {
 *   @Test
 *   void prometheusScraperHandlesSlowJmxAttributeReads(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an
 *       {@code ExtensionConfigurationException} at {@code beforeAll}.
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
@Repeatable(ChaosJmxGetAttrDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.JMX_GET_ATTR)
public @interface ChaosJmxGetAttrDelay {

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
   * @ChaosJmxGetAttrDelay(id = "primary",  probability = 0.001)
   * @ChaosJmxGetAttrDelay(id = "replica",  probability = 0.01)
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
    ChaosJmxGetAttrDelay[] value();
  }
}
