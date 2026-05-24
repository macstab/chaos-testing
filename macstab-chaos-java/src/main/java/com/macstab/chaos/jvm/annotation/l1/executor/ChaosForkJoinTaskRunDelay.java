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
 * Parks every {@link java.util.concurrent.ForkJoinPool} worker thread for {@link #delayMs} to
 * {@link #maxDelayMs} milliseconds before executing a {@link java.util.concurrent.ForkJoinTask},
 * slowing down all parallel computations that use the common pool or any custom {@code
 * ForkJoinPool} without affecting task submission.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * FORK_JOIN_TASK_RUN} operation with the {@code delay} effect. It intercepts the moment a {@code
 * ForkJoinPool} worker picks up a {@code ForkJoinTask} and calls its {@code doExec()} or {@code
 * exec()} entry point. The worker thread is parked for the configured duration before the task's
 * compute method runs.
 *
 * <p>This annotation targets the {@code ForkJoinPool} execution path specifically. For {@code
 * ThreadPoolExecutor}-based pools, use {@link ChaosExecutorWorkerRunDelay} instead. Applications
 * that use {@code CompletableFuture} with the common pool, {@code Stream.parallel()}, or explicit
 * {@code ForkJoinPool} invocation are all covered by this annotation.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code ForkJoinTask.doExec()} (or the
 * equivalent internal execution entry point). When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor fires on the {@code ForkJoinWorkerThread} that has stolen or dequeued the
 *       task from its work-stealing deque.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code [delayMs,
 *       maxDelayMs]}), parking the worker thread.
 *   <li>After the sleep, the task's compute method executes normally, and the task's result is
 *       stored for any joiner waiting on {@code ForkJoinTask.join()} or {@code get()}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code forkJoinPool.invoke(task)} eventually returns the correct result — the task runs
 *       correctly, just delayed.
 *   <li>The wall-clock time of any parallel computation (parallel streams, recursive fork/join
 *       problems) increases by at least {@link #delayMs} ms per task stolen.
 *   <li>{@code CompletableFuture} chains backed by the common pool complete later, causing
 *       downstream {@code get(timeout)} calls to time out if the delay exceeds their budget.
 *   <li>Work-stealing patterns are disrupted: workers that would normally steal tasks from each
 *       other are all sleeping simultaneously, eliminating the throughput benefit of work stealing.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a parallel stream processing pipeline runs on the
 * JVM common pool; a GC pause or CPU throttle causes all {@code ForkJoinWorkerThread}s to stall
 * simultaneously — the throughput of the parallel computation collapses to sequential speed or
 * worse, causing downstream systems to observe unexpected latency.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code ForkJoinTask.doExec()} — the
 * internal method that dispatches to {@code exec()} (for {@code RecursiveAction}/{@code
 * RecursiveTask}) or to {@code Callable.call()} (for {@code AdaptedCallable}). This is a package-
 * private method in {@code java.util.concurrent.ForkJoinTask}, so the agent must use retrans-
 * formation with bootstrap class loader access.
 *
 * <p><strong>Work-stealing queue interaction.</strong> {@code ForkJoinPool} uses a work-stealing
 * deque per worker: tasks are pushed to the worker's own deque (LIFO) and stolen from other
 * workers' deques (FIFO). When a worker sleeps inside the delay interceptor, it is not available to
 * steal from other workers. This reduces the effective parallelism: if all workers sleep
 * simultaneously, the pool temporarily behaves as a serial executor until the first worker wakes.
 *
 * <p><strong>Common pool scope.</strong> The common pool is shared across all code in the JVM that
 * uses {@code ForkJoinPool.commonPool()}, including parallel streams, default {@code
 * CompletableFuture} execution, and some reactive libraries. This annotation affects all of them
 * simultaneously; there is no per-pool filter at the L1 primitive level.
 *
 * <p><strong>Interaction with ManagedBlocker.</strong> If a task uses {@code
 * ForkJoinPool.managedBlock} to signal that it is about to block, the pool may spawn a spare worker
 * to compensate. The compensation mechanism does not fire for the chaos sleep (it is invisible to
 * the pool's blocking detection), so the pool does not add spare workers during the injected delay.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorWorkerRunDelay} targets
 * {@code ThreadPoolExecutor} workers. {@link ChaosForkJoinTaskRunReject} throws an exception from
 * the ForkJoin worker instead of delaying. Use this annotation when the target uses {@code
 * ForkJoinPool} specifically.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosForkJoinTaskRunDelay(delayMs = 200)
 * class ParallelStreamSlowTest {
 *
 *   @Test
 *   void parallelStreamSlowerThanTimeoutWhenTasksAreDelayed(AppConnectionInfo info) {
 *     assertThatThrownBy(() -> client.fetchParallel(info, 1, TimeUnit.SECONDS))
 *         .isInstanceOf(TimeoutException.class);
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
 * @see com.macstab.chaos.jvm.api.OperationType#FORK_JOIN_TASK_RUN
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosForkJoinTaskRunReject
 * @see ChaosExecutorWorkerRunDelay
 */
@Repeatable(ChaosForkJoinTaskRunDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.FORK_JOIN_TASK_RUN)
public @interface ChaosForkJoinTaskRunDelay {

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
   * @ChaosForkJoinTaskRunDelay(id = "primary",  probability = 0.001)
   * @ChaosForkJoinTaskRunDelay(id = "replica",  probability = 0.01)
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
    ChaosForkJoinTaskRunDelay[] value();
  }
}
