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
 * Delays every {@code MBeanServer.invoke()} call by a configurable number of milliseconds,
 * simulating a slow JMX management operation such as a cache flush, thread-pool resize, or
 * log-level change.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code JMX_INVOKE} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code
 *       javax.management.MBeanServer.invoke(ObjectName, String, Object[], String[])} in the target
 *       container's JVM.
 *   <li>Before forwarding the call to the MBean, the interceptor parks the calling thread for a
 *       duration sampled uniformly between {@link #delayMs()} and {@link #maxDelayMs()}
 *       milliseconds.
 *   <li>After the delay, the real MBean operation executes and the result is returned to the
 *       caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Hot-reload operations slow.</strong> JMX-triggered operations such as {@code
 *       reloadConfiguration} or {@code resetStatistics} will complete late; assert that the
 *       management client (CLI, admin console) handles the delay gracefully and does not time out.
 *   <li><strong>Connection pool management delayed.</strong> Frameworks that expose pool-resize
 *       operations via JMX will apply the resize after the injected delay; assert that the pool's
 *       old configuration remains stable during the delay window rather than entering an
 *       inconsistent state.
 *   <li><strong>Garbage collection trigger delayed.</strong> JVM MBeans expose GC trigger
 *       operations; assert that the caller does not assume GC has run immediately after the invoke
 *       returns.
 *   <li><strong>Production failure mode:</strong> slow JMX invoke operations can block the
 *       management thread in application servers that process JMX requests on a single thread,
 *       effectively making the server unmanageable for the duration of the delay.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code MBeanServer.invoke()} is the JMX equivalent of a remote procedure call: it identifies
 * an operation on a named MBean by operation name and signature and invokes it with the supplied
 * arguments. The agent intercepts at the {@code MBeanServer} interface level, before the MBean
 * registry resolves the target MBean. The delay fires on the thread that issued the invoke, which
 * may be a JMX connector thread (for remote callers) or the application's own management thread.
 *
 * <p>Unlike attribute reads, MBean operations are often side-effecting (they change state, trigger
 * actions, or produce side effects). A delay therefore means those side effects are delayed, not
 * that they are repeated. Tests that assert "the cache was flushed" must allow for the full delay
 * before making the assertion.
 *
 * <p>The delay interacts with JMX connector timeouts: a remote JMX client (e.g. JConsole, a custom
 * management script) will time out if the invoke takes longer than the client's configured request
 * timeout. Tests can use this to validate that the management tooling handles server-side slowness
 * correctly — for example by displaying a "request timed out" message rather than hanging
 * indefinitely.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJmxInvokeDelay(delayMs = 1_000, maxDelayMs = 3_000)
 * class JmxInvokeDelayTest {
 *   @Test
 *   void managementClientHandlesSlowJmxOperation(ConnectionInfo info) { ... }
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
@Repeatable(ChaosJmxInvokeDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.JMX_INVOKE)
public @interface ChaosJmxInvokeDelay {

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
   * @ChaosJmxInvokeDelay(id = "primary",  probability = 0.001)
   * @ChaosJmxInvokeDelay(id = "replica",  probability = 0.01)
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
    ChaosJmxInvokeDelay[] value();
  }
}
