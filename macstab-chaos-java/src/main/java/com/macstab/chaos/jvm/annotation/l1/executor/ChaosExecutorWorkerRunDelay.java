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
 * Parks every executor worker thread for {@link #delayMs} to {@link #maxDelayMs} milliseconds when
 * it picks up a task to execute — slowing down task throughput without affecting task submission or
 * preventing any task from eventually running.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * EXECUTOR_WORKER_RUN} operation with the {@code delay} effect. It intercepts the moment a worker
 * thread in a {@code ThreadPoolExecutor} or compatible executor dequeues a task and is about to
 * call {@code task.run()} (or {@code task.call()} for callables). The worker thread is parked for
 * the configured duration before the task body executes.
 *
 * <p>This is the execution-side analogue of {@link ChaosExecutorSubmitDelay}, which delays the
 * submission caller. Here, the delay is on the worker thread, invisible to the submitter — the
 * submitter's {@code Future} is created immediately; it just takes longer to complete.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on the worker thread's task-dispatch entry
 * point inside {@code ThreadPoolExecutor} (specifically around the {@code runWorker} loop's call to
 * {@code task.run()}). When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor fires on the worker thread after the task has been dequeued.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code [delayMs,
 *       maxDelayMs]}), parking the worker thread for the configured duration.
 *   <li>After the sleep, {@code task.run()} executes normally. The task completes and the worker
 *       loops back to dequeue the next task.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code future.get()} eventually returns the correct value — the task does run; it just
 *       takes at least {@link #delayMs} ms longer to start.
 *   <li>Task throughput (tasks completed per second) drops proportionally to the sleep duration and
 *       the pool size — each worker sleeps before each task.
 *   <li>{@code future.get(timeout, unit)} throws {@link java.util.concurrent.TimeoutException} if
 *       {@link #delayMs} exceeds the caller's wait budget.
 *   <li>Monitoring dashboards will show executor queue depth growing as tasks back up while workers
 *       sleep, then draining when the delay annotation is removed.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a GC pause or lock contention spike inside the
 * thread pool causes every worker thread to stall for hundreds of milliseconds before picking up
 * the next task; tasks accumulate in the queue faster than they are drained, eventually triggering
 * rejection or memory pressure.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets the task-execution entry point inside
 * {@code java.util.concurrent.ThreadPoolExecutor}'s {@code runWorker} loop — specifically the call
 * site that invokes {@code task.run()} (where {@code task} is the {@code Runnable} or {@code
 * FutureTask} dequeued from the work queue). This is a JDK internal method, intercepted via Byte
 * Buddy retransformation of the bootstrap-loaded class.
 *
 * <p><strong>Worker thread semantics.</strong> During the sleep, the worker thread does not hold
 * the executor's main lock; the lock was released when the task was dequeued. Other workers
 * continue dequeuing and sleeping independently. With a pool of {@code N} workers and a sleep of
 * {@code D} ms, the effective task completion rate drops to approximately {@code N / D} tasks per
 * second (ignoring task run time).
 *
 * <p><strong>Interaction with ForkJoinPool.</strong> {@code ForkJoinPool} workers use {@code
 * ForkJoinTask.doExec()} rather than a simple {@code Runnable.run()} call. The {@code
 * FORK_JOIN_TASK_RUN} operation (covered by {@link ChaosForkJoinTaskRunDelay}) targets that path.
 * Use {@code ChaosExecutorWorkerRunDelay} for traditional {@code ThreadPoolExecutor}-based pools;
 * use {@link ChaosForkJoinTaskRunDelay} for {@code ForkJoinPool} tasks.
 *
 * <p><strong>Cascading effects.</strong> With all workers sleeping before every task, any component
 * that waits on a pool-backed {@code Future} within a request deadline will time out. Retry logic
 * that resubmits on timeout may cause queue runaway: each timed-out retry adds a new task that also
 * sleeps, worsening the backlog.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorSubmitDelay} slows the
 * submitter; this annotation slows the worker. {@link ChaosExecutorWorkerRunReject} throws an
 * exception from the worker instead of delaying it. Combining both delay annotations stacks their
 * effects: the submitter sleeps and then the worker sleeps again before execution.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosExecutorWorkerRunDelay(delayMs = 300, maxDelayMs = 300)
 * class WorkerRunSlowTest {
 *
 *   @Test
 *   void taskFutureTimesOutWhenWorkerIsSlow(AppConnectionInfo info) {
 *     Future<String> result = client.submitBackgroundTask(info);
 *     assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS))
 *         .isInstanceOf(TimeoutException.class);
 *     // task does eventually complete after the delay
 *     assertThat(result.get(2, TimeUnit.SECONDS)).isNotNull();
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
 * @see com.macstab.chaos.jvm.api.OperationType#EXECUTOR_WORKER_RUN
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosExecutorWorkerRunReject
 * @see ChaosExecutorSubmitDelay
 * @see ChaosForkJoinTaskRunDelay
 */
@Repeatable(ChaosExecutorWorkerRunDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.EXECUTOR_WORKER_RUN)
public @interface ChaosExecutorWorkerRunDelay {

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
   * @ChaosExecutorWorkerRunDelay(id = "primary",  probability = 0.001)
   * @ChaosExecutorWorkerRunDelay(id = "replica",  probability = 0.01)
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
    ChaosExecutorWorkerRunDelay[] value();
  }
}
