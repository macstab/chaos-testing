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
 * Silently suppresses every {@code System.exit()} call, preventing the application from
 * terminating the JVM process even when it explicitly requests shutdown.
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
 *   <li>The interceptor discards the call and returns without forwarding to the JVM; no shutdown
 *       hooks run and the process continues.
 *   <li>The calling thread resumes execution after the suppressed exit point, which is typically
 *       unexpected and may cause the thread to encounter code it was never designed to reach.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Application enters undefined state.</strong> Code paths after a suppressed
 *       {@code System.exit()} call are normally unreachable; the application may attempt to
 *       continue initialisation in a partially constructed state, producing NPEs or state-machine
 *       violations. Assert that the container's liveness probe fails and triggers a restart.
 *   <li><strong>Graceful shutdown never completes.</strong> A suppressed exit means shutdown hooks
 *       never run, so database connections, file locks, and message-queue consumers are not
 *       released; assert that the orchestration layer (Kubernetes) detects the stalled pod and
 *       sends SIGKILL after the grace period.
 *   <li><strong>Rolling update stalls.</strong> A pod that should exit during a rolling update but
 *       doesn't will block the deployment controller from reaching the desired replica count.
 *   <li><strong>Production failure mode:</strong> a process that ignores its own exit request
 *       will hold locks indefinitely, block cluster rebalancing, and consume resources while
 *       appearing healthy to observers that only check process liveness and not logical health.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code System.exit(int)} performs three steps: it calls the registered security manager's
 * {@code checkExit()} (if any), runs all registered shutdown hooks in parallel, then calls
 * {@code Runtime.halt()} to terminate the JVM. Suppression at the {@code System.exit()} level
 * prevents all three steps; the calling thread unwinds back to its caller as if exit had not been
 * requested.
 *
 * <p>The agent uses a Byte Buddy method-entry interceptor that checks the suppression flag and
 * short-circuits before the security manager check. Because exit is now a no-op at the Java level,
 * any calling code that does not expect the method to return (such as a {@code main()} method that
 * calls {@code System.exit(0)} as its last statement) will proceed into whatever code follows — or,
 * if it is the bottom of the call stack, the thread will simply complete and die. If all
 * non-daemon threads die, the JVM will exit naturally even with the suppression active.
 *
 * <p>This annotation is commonly used in combination with a health-check chaos annotation to
 * simulate a pod that is unresponsive to SIGTERM and can only be terminated by SIGKILL. The test
 * validates that the application's deployment configuration ({@code terminationGracePeriodSeconds},
 * pre-stop hooks, readiness probes) correctly isolates and eventually recycles the stalled pod.
 *
 * <p>Use with caution on containers that rely on {@code System.exit()} for clean-up: suppression
 * will cause resource leaks (open file descriptors, unreleased locks) to accumulate, potentially
 * affecting the stability of other containers in the same test scenario.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSystemExitRequestSuppress
 * class SuppressedExitTest {
 *   @Test
 *   void kubernetesRecyclesStalledPodViaForceKill(ConnectionInfo info) { ... }
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
 * @see com.macstab.chaos.jvm.api.OperationType#SYSTEM_EXIT_REQUEST
 * @see com.macstab.chaos.jvm.api.ChaosSelector#jvmRuntime(java.util.Set)
 */
@Repeatable(ChaosSystemExitRequestSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.SYSTEM_EXIT_REQUEST)
public @interface ChaosSystemExitRequestSuppress {

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
   * @ChaosSystemExitRequestSuppress(id = "primary",  probability = 0.001)
   * @ChaosSystemExitRequestSuppress(id = "replica",  probability = 0.01)
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
    ChaosSystemExitRequestSuppress[] value();
  }
}
