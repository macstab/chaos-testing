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
 * Blocks every {@link java.util.concurrent.ExecutorService#submit ExecutorService.submit} call on
 * the submitting thread until the test releases the gate or {@link #maxBlockMs} elapses, giving the
 * test precise control over the exact moment a task enters the executor's queue.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * EXECUTOR_SUBMIT} operation with the {@code gate} effect. Unlike {@link ChaosExecutorSubmitDelay},
 * which parks the submitting thread for a fixed random duration, the gate effect blocks the thread
 * indefinitely on an internal {@code CountDownLatch} (or equivalent barrier) until the test
 * framework signals release — or until {@link #maxBlockMs} ms have elapsed as a safety timeout.
 *
 * <p>The gate is the correct primitive when a test needs to assert on the application's in-flight
 * state at a precise moment: the gate pauses all new task submissions while existing tasks drain,
 * so the test can inspect queue depth, thread-pool state, or other metrics with no race.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on the {@code submit} methods of {@code
 * ExecutorService} and {@code ForkJoinPool}. When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the submitting thread before the task enters the queue.
 *   <li>The gate effect acquires an internal latch and blocks the thread with {@code
 *       latch.await(maxBlockMs, MILLISECONDS)}.
 *   <li>The test calls the agent's gate-release API; the latch counts down, unblocking the thread.
 *   <li>Alternatively, if {@link #maxBlockMs} elapses before the gate is released, the latch times
 *       out and the thread continues — the submission proceeds normally after the timeout.
 *   <li>After unblocking, the original {@code submit} body executes and the task enters the queue.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code executor.submit(task)} does not return until the gate is released or {@link
 *       #maxBlockMs} ms pass — the call is synchronously blocked.
 *   <li>While the gate is held, the executor's work queue does not grow — no new tasks are enqueued
 *       — making queue-depth assertions deterministic.
 *   <li>The returned {@code Future} completes normally after the gate is released and the task
 *       eventually runs.
 *   <li>Caller threads that hold locks while submitting tasks will hold those locks for the
 *       duration of the gate block, potentially causing secondary contention in the application.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a scheduler submits periodic tasks to a shared pool,
 * but the pool becomes saturated during a traffic spike — submissions block behind a full queue for
 * an unpredictable duration; the gate replicates this condition in a controlled, repeatable way.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code submit} overloads on {@code
 * java.util.concurrent.ExecutorService} and {@code java.util.concurrent.ForkJoinPool} via Byte
 * Buddy retransformation of bootstrap-loaded JDK classes. The latch used by the gate is managed by
 * the agent's in-process gate registry, keyed by the plan rule identifier.
 *
 * <p><strong>Gate release mechanism.</strong> The test calls the agent's HTTP control API (or the
 * in-process API if running in the same JVM) to send a release signal to the named gate. The agent
 * decrements the latch, which is observed by all threads blocked in the gate interceptor. Multiple
 * threads that hit the gate simultaneously are all released at once when the latch reaches zero.
 *
 * <p><strong>maxBlockMs safety timeout.</strong> If the test framework fails to release the gate
 * (e.g., due to test timeout or unexpected early failure), the {@link #maxBlockMs} ceiling prevents
 * the application thread from blocking forever, allowing the container to eventually reach a clean
 * state for the next test.
 *
 * <p><strong>Interaction with virtual threads.</strong> Under Project Loom, virtual threads blocked
 * on the latch are unmounted from their carrier, freeing the platform thread for other work during
 * the block. This means a gated submit on a virtual thread does not starve the platform thread pool
 * — though the virtual thread itself is still frozen until the gate is released.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorSubmitDelay} releases
 * automatically after a fixed sleep. {@link ChaosExecutorSubmitReject} never admits the task. The
 * gate is the only primitive that gives the test explicit, external control over the exact timing
 * of when the submission is admitted.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosExecutorSubmitGate(maxBlockMs = 5000)
 * class GatedSubmitTest {
 *
 *   @Test
 *   void queueDepthIsZeroWhileGateHeld(AppConnectionInfo info, ChaosGateControl gate)
 *       throws Exception {
 *     // trigger a background task that will block on submit
 *     client.triggerBackgroundTaskAsync(info);
 *     // assert the queue is not yet populated
 *     assertThat(metrics.executorQueueDepth(info)).isEqualTo(0);
 *     // release the gate; task enters the queue
 *     gate.release();
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code
 *       beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#EXECUTOR_SUBMIT
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosExecutorSubmitDelay
 * @see ChaosExecutorSubmitReject
 */
@Repeatable(ChaosExecutorSubmitGate.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.GateTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.EXECUTOR_SUBMIT)
public @interface ChaosExecutorSubmitGate {

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
   * @ChaosExecutorSubmitGate(id = "primary",  probability = 0.001)
   * @ChaosExecutorSubmitGate(id = "replica",  probability = 0.01)
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
    ChaosExecutorSubmitGate[] value();
  }
}
