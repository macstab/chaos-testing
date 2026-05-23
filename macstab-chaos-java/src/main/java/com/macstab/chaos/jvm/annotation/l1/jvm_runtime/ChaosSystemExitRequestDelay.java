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
 * Delays every {@code System.exit()} call by a configurable number of milliseconds, simulating a
 * JVM that is slow to honour a shutdown request and stretching the time between signal receipt and
 * actual process termination.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code SYSTEM_EXIT_REQUEST} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code System.exit(int)} in the target container's
 *       JVM.
 *   <li>Before forwarding the call, the interceptor parks the calling thread for a duration sampled
 *       uniformly between {@link #delayMs()} and {@link #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real {@code System.exit()} executes; shutdown hooks run and the JVM
 *       terminates with the original status code.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Kubernetes SIGTERM grace period exhausted.</strong> Kubernetes sends SIGTERM then
 *       waits up to {@code terminationGracePeriodSeconds} before sending SIGKILL; if the
 *       application's shutdown hook calls {@code System.exit()} and the injected delay exceeds the
 *       grace period, the container will be forcibly killed. Assert that the application cleans up
 *       state within the grace period even when exit is slow.
 *   <li><strong>Rolling deployment leaves traffic on dying pod.</strong> If the load balancer is
 *       not notified promptly because the exit is delayed, in-flight requests may be routed to a
 *       node that is about to die. Assert that the service mesh or readiness probe removes the pod
 *       from rotation before {@code System.exit()} actually executes.
 *   <li><strong>Shutdown hook ordering races.</strong> A delayed {@code System.exit()} call gives
 *       other threads more time to observe the shutdown signal and attempt clean-up; assert that
 *       shutdown hooks are idempotent.
 *   <li><strong>Production failure mode:</strong> a delayed exit prevents the container from
 *       restarting promptly after a crash, causing Kubernetes's crash-loop backoff to accumulate
 *       and extending the service's recovery time beyond SLA.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code System.exit()} ultimately calls {@code Runtime.halt()} (or the equivalent JVM
 * intrinsic) after running all registered shutdown hooks. Intercepting it at the
 * {@code System.exit()} level means the delay fires before any shutdown hook runs, extending the
 * entire shutdown sequence. Intercepting at the {@code Runtime.halt()} level would insert the delay
 * after hooks complete but before the JVM destroys its threads.
 *
 * <p>The agent wraps {@code System.exit()} with a Byte Buddy delegation that parks the invoking
 * thread for the configured duration, then delegates to the real implementation. Because the
 * calling thread is parked rather than busy-spinning, other threads continue to execute during the
 * injected delay, which can expose race conditions in shutdown hook implementations that assume
 * exit is imminent.
 *
 * <p>This annotation is particularly useful when combined with liveness-probe chaos (via the
 * connection-level annotations) to test the full shutdown sequence: probe → liveness failure →
 * SIGTERM → {@code System.exit()} delay → SIGKILL. The end-to-end test validates the entire
 * Kubernetes pod lifecycle under adverse conditions.
 *
 * <p>Note that the delay applies only once per {@code System.exit()} call. If the application
 * catches and re-throws an exception that eventually leads to a second {@code System.exit()}, the
 * delay is applied again, which can compound.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSystemExitRequestDelay(delayMs = 8_000, maxDelayMs = 8_000)
 * class SlowShutdownTest {
 *   @Test
 *   void podTerminatesCleanlyWithinGracePeriod(ConnectionInfo info) { ... }
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
@Repeatable(ChaosSystemExitRequestDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.SYSTEM_EXIT_REQUEST)
public @interface ChaosSystemExitRequestDelay {

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
   * @ChaosSystemExitRequestDelay(id = "primary",  probability = 0.001)
   * @ChaosSystemExitRequestDelay(id = "replica",  probability = 0.01)
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
    ChaosSystemExitRequestDelay[] value();
  }
}
