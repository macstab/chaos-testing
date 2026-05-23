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
 * Throws a configurable exception at every {@code MBeanServer.getAttribute()} call site,
 * simulating a broken MBean, a deregistered MBean, or an attribute that cannot be read.
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
 *   <li>Before the MBean is consulted, the interceptor instantiates the exception class named by
 *       {@link #exceptionClassName()} with {@link #message()} and throws it.
 *   <li>The calling thread unwinds from the throw site; no attribute value is returned.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Monitoring scraper receives errors.</strong> Jolokia or custom JMX clients that
 *       read attributes will receive an exception; assert that the monitoring pipeline marks the
 *       metric as unavailable rather than recording a zero or stale value.
 *   <li><strong>Auto-scaling logic receives null or error.</strong> Auto-scalers that drive
 *       scaling decisions from JMX metrics will operate without data; assert that the scaler
 *       defaults to the last known value or to a safe scaling policy rather than crashing.
 *   <li><strong>JMX-based health check fails.</strong> Management probes that read JVM health
 *       attributes will receive exceptions; assert that the probe reports the node as unhealthy
 *       and triggers a restart, rather than interpreting the exception as a transient error and
 *       continuing to serve traffic.
 *   <li><strong>Production failure mode:</strong> a deregistered or replaced MBean (e.g. after
 *       an application redeployment on a running server) causes all attribute reads on the old
 *       {@code ObjectName} to throw {@code InstanceNotFoundException}; monitoring dashboards go
 *       blank and alerting rules that depend on JMX data stop firing.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The natural exception types for JMX attribute failures are:
 * {@code javax.management.InstanceNotFoundException} (the MBean is not registered),
 * {@code javax.management.AttributeNotFoundException} (the MBean does not expose the requested
 * attribute), and {@code javax.management.MBeanException} (the MBean's getter threw an exception).
 * Each corresponds to a different root cause and exercises a different branch in the caller's
 * exception handler; select {@link #exceptionClassName()} to match the scenario under test.
 *
 * <p>The exception is thrown before any MBean-side code runs, so the MBean's state is not changed.
 * Callers that cache the attribute value and fall back to the cache on exception will not observe
 * stale data from the MBean itself; they will simply use whatever was in the cache at the time the
 * chaos was activated. Tests should verify this caching behaviour if it is part of the expected
 * resilience strategy.
 *
 * <p>JMX attribute reads issued from the JVM's own internal monitoring infrastructure (e.g. the
 * garbage collector's self-reporting via MBeans) are also intercepted if they go through
 * {@code MBeanServer.getAttribute()}. However, most internal JVM monitoring uses direct field
 * access rather than the JMX API, so the impact on JVM-internal monitoring is typically small.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJmxGetAttrInjectException(
 *     exceptionClassName = "javax.management.InstanceNotFoundException",
 *     message = "MBean not registered: com.example:type=Cache")
 * class JmxAttrFailureTest {
 *   @Test
 *   void monitoringPipelineHandlesMissingMbeanGracefully(ConnectionInfo info) { ... }
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
@Repeatable(ChaosJmxGetAttrInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.JMX_GET_ATTR)
public @interface ChaosJmxGetAttrInjectException {

  /**
   * @return binary class name of the exception to throw (e.g. "java.io.IOException")
   */
  String exceptionClassName() default "java.io.IOException";

  /**
   * @return exception message
   */
  String message() default "injected by chaos L1";

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
   * @ChaosJmxGetAttrInjectException(id = "primary",  probability = 0.001)
   * @ChaosJmxGetAttrInjectException(id = "replica",  probability = 0.01)
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
    ChaosJmxGetAttrInjectException[] value();
  }
}
