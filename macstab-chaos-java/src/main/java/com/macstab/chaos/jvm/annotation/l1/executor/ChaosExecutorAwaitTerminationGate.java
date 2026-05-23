/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.executor;

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
 * Blocks the calling thread on every {@link java.util.concurrent.ExecutorService#awaitTermination
 * awaitTermination(timeout, unit)} call until the test releases the gate or {@link #maxBlockMs}
 * elapses, giving the test precise control over exactly when the executor's termination wait is
 * allowed to begin.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * EXECUTOR_AWAIT_TERMINATION} operation with the {@code gate} effect. Unlike
 * {@link ChaosExecutorAwaitTerminationDelay}, which applies a fixed random sleep and then releases
 * automatically, the gate blocks the calling thread indefinitely on an internal barrier until the
 * test framework explicitly releases it — or until the {@link #maxBlockMs} safety timeout fires.
 *
 * <p>The gate is the correct primitive when the test needs to assert on the application's in-flight
 * state precisely at the moment the termination wait is attempted: for example, to verify that
 * in-flight tasks are still running, that metrics are emitted, or that health checks transition
 * to a specific state before the drain is allowed to complete.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code ExecutorService.awaitTermination}.
 * When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the calling thread before the method's blocking wait begins.
 *   <li>The gate effect acquires an internal latch and blocks with
 *       {@code latch.await(maxBlockMs, MILLISECONDS)}.
 *   <li>The test calls the agent's gate-release API, counting down the latch.
 *   <li>Alternatively, if {@link #maxBlockMs} elapses, the latch times out and the thread
 *       proceeds automatically.
 *   <li>After the gate is released, {@code awaitTermination} executes normally.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code executor.awaitTermination(timeout, unit)} does not return until the gate is released
 *       or {@link #maxBlockMs} ms pass — the call is synchronously blocked before its timeout
 *       even starts counting.
 *   <li>While the gate is held, the executor may finish draining (if tasks complete quickly) — so
 *       {@code awaitTermination} may return {@code true} immediately after the gate is released.
 *   <li>Any thread that calls {@code awaitTermination} while the gate is held will block at the
 *       gate — multiple threads can be held simultaneously.
 *   <li>The executor's worker threads continue running while the calling thread is gated; the gate
 *       only pauses the waiting side, not the executing side.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a service mesh sidecar delays readiness probes
 * during rolling deployment, causing the application's shutdown hook to call
 * {@code awaitTermination} before the mesh has deregistered the pod — requests continue arriving
 * after the drain window starts, extending actual termination time beyond the configured budget.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets
 * {@code java.util.concurrent.ExecutorService#awaitTermination(long, TimeUnit)} on all concrete
 * implementations via Byte Buddy retransformation. The latch is managed by the agent's gate
 * registry, keyed by the plan rule identifier, and shared across all threads that hit the gate
 * simultaneously.
 *
 * <p><strong>Timeout budget behaviour.</strong> The caller-specified timeout is passed unchanged to
 * the real {@code awaitTermination} after the gate is released. This means the caller's timeout
 * budget begins at gate-release time, not at call time — the total block can exceed
 * {@code timeout + maxBlockMs} if both fire at their maximum.
 *
 * <p><strong>Gate release and multiple callers.</strong> If multiple threads call
 * {@code awaitTermination} concurrently and all hit the gate, a single release signal unblocks all
 * of them simultaneously (the latch counts down to zero). If precise per-thread release control is
 * needed, configure separate rules with distinct {@link #id()} values.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorAwaitTerminationDelay}
 * applies a fixed sleep and releases automatically — no test coordination is required.
 * The gate requires an explicit release but enables the test to assert on state between the shutdown
 * and the drain — the key use-case where precise timing matters.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosExecutorAwaitTerminationGate(maxBlockMs = 10_000)
 * class GatedTerminationTest {
 *
 *   @Test
 *   void tasksStillRunningWhileAwaitTerminationIsBlocked(
 *       AppConnectionInfo info, ChaosGateControl gate) throws Exception {
 *     client.triggerGracefulShutdown(info); // calls shutdown() + awaitTermination() internally
 *     // at this point awaitTermination is blocked at the gate; tasks may still run
 *     assertThat(metrics.runningTaskCount(info)).isGreaterThan(0);
 *     // release the gate; awaitTermination proceeds
 *     gate.release();
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#EXECUTOR_AWAIT_TERMINATION
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosExecutorAwaitTerminationDelay
 * @see ChaosExecutorShutdownDelay
 */
@Repeatable(ChaosExecutorAwaitTerminationGate.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.GateTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.EXECUTOR_AWAIT_TERMINATION)
public @interface ChaosExecutorAwaitTerminationGate {

  /**
   * @return maximum block duration in milliseconds
   */
  long maxBlockMs() default 30_000L;

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
   * @ChaosExecutorAwaitTerminationGate(id = "primary",  probability = 0.001)
   * @ChaosExecutorAwaitTerminationGate(id = "replica",  probability = 0.01)
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
    ChaosExecutorAwaitTerminationGate[] value();
  }
}
