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
 * Throws a configurable exception at every {@code MBeanServer.invoke()} call site, simulating a
 * failed JMX management operation such as an MBean that is deregistered or an operation that
 * throws at its implementation level.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code JMX_INVOKE} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to
 *       {@code javax.management.MBeanServer.invoke(ObjectName, String, Object[], String[])} in the
 *       target container's JVM.
 *   <li>Before the MBean is located or its operation is dispatched, the interceptor instantiates
 *       the exception class named by {@link #exceptionClassName()} with {@link #message()} and
 *       throws it.
 *   <li>The calling thread unwinds from the throw site; no MBean operation executes.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Remote management operations fail.</strong> Scripts and admin consoles that
 *       trigger JMX operations (cache invalidation, thread-pool resize, log-level change) will
 *       receive exceptions; assert that the management tooling reports the error clearly and
 *       does not assume the operation succeeded.
 *   <li><strong>Automated remediation broken.</strong> Systems that use JMX invocations to
 *       trigger self-healing actions (e.g. clearing a poison-message queue via JMX) will fail;
 *       assert that the remediation system has a fallback path.
 *   <li><strong>Metrics reset operations skipped.</strong> Operations that reset MBean counters
 *       on a schedule will be skipped; assert that counter overflow or indefinite accumulation
 *       does not break downstream consumers that assume bounded counter values.
 *   <li><strong>Production failure mode:</strong> Kubernetes Operator controllers that manage
 *       application state via JMX invoke operations will see every control action fail, leaving
 *       the application in its current state indefinitely and causing the Operator to keep
 *       retrying until it enters an error backoff.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The natural exception types for JMX invoke failures are:
 * {@code javax.management.InstanceNotFoundException} (MBean not registered),
 * {@code javax.management.ReflectionException} (method not found on the MBean), and
 * {@code javax.management.MBeanException} (operation threw an application exception). The last
 * wraps the application exception as its cause, so the caller typically needs to unwrap it with
 * {@code getCause()} to identify the root cause.
 *
 * <p>The exception is thrown before any MBean-side code runs, so the MBean's state is unchanged.
 * Operations that were meant to transition state (e.g. "start", "stop", "flush") will not have
 * executed. Tests that assert post-operation state should therefore observe the pre-operation state
 * and verify that the error is handled without a partial or inconsistent state change.
 *
 * <p>Because the exception is thrown at the {@code MBeanServer} level, it is indistinguishable
 * from a real MBean-level failure as seen by the caller. Remote callers connected via JMX/RMI
 * will receive the exception serialised over the wire as if the server had thrown it. This
 * correctly simulates the observable behaviour of a broken management infrastructure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJmxInvokeInjectException(
 *     exceptionClassName = "javax.management.MBeanException",
 *     message = "Cache flush operation failed: internal error")
 * class JmxInvokeFailureTest {
 *   @Test
 *   void selfHealingFallsBackToAlternativeRemediation(ConnectionInfo info) { ... }
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
@Repeatable(ChaosJmxInvokeInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.JMX_INVOKE)
public @interface ChaosJmxInvokeInjectException {

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
   * @ChaosJmxInvokeInjectException(id = "primary",  probability = 0.001)
   * @ChaosJmxInvokeInjectException(id = "replica",  probability = 0.01)
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
    ChaosJmxInvokeInjectException[] value();
  }
}
