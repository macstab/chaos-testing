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
 * Parks the calling thread for {@link #delayMs} to {@link #maxDelayMs} milliseconds inside every
 * {@link java.util.concurrent.ExecutorService#submit ExecutorService.submit} and
 * {@link java.util.concurrent.ForkJoinPool#submit ForkJoinPool.submit} call, stretching the
 * wall-clock time of task hand-off without preventing the task from eventually being queued.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * EXECUTOR_SUBMIT} operation with the {@code delay} effect. It intercepts every task submission to
 * {@code ExecutorService} and {@code ForkJoinPool} implementations in the container JVM and parks
 * the submitting thread for a configurable duration before allowing the submission to proceed. The
 * task is eventually enqueued and executed normally — the delay only stretches the time between the
 * caller's submit call and the point at which the executor accepts the task.
 *
 * <p>This is the submit-path analogue of {@link ChaosExecutorWorkerRunDelay}, which delays task
 * <em>execution</em> rather than task submission.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on the {@code submit} methods of
 * {@code ExecutorService} and {@code ForkJoinPool}. When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the submitting thread before the task enters the executor's
 *       work queue.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code
 *       [delayMs, maxDelayMs]} when {@code maxDelayMs > delayMs}), parking the submitting thread.
 *   <li>After the sleep, the original {@code submit} body executes: the task is placed on the
 *       executor's queue and a {@code Future} is returned to the caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code executor.submit(task)} returns normally but takes at least {@link #delayMs} ms
 *       longer than usual.
 *   <li>The returned {@code Future} eventually completes — the task itself is unaffected.
 *   <li>High-throughput submission loops accumulate one sleep per task, causing unbounded latency
 *       growth proportional to the submission rate.
 *   <li>Applications that measure task-queue latency (time from submit to execution start) will
 *       observe inflated figures.
 *   <li>Bounded queues that the caller fills before consuming may trigger
 *       {@code RejectedExecutionException} sooner than expected, because the delay stretches the
 *       window during which the queue is full.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a high-frequency event processor submits tasks
 * faster than the thread pool can drain them; momentary submission latency spikes from a slow
 * scheduler cause queue buildup that triggers back-pressure or rejection policies earlier than
 * load tests predicted.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets the {@code submit(Callable)},
 * {@code submit(Runnable)}, and {@code submit(Runnable, T)} overloads on
 * {@code java.util.concurrent.ExecutorService} (and concrete implementations such as
 * {@code ThreadPoolExecutor} and {@code ScheduledThreadPoolExecutor}) as well as
 * {@code java.util.concurrent.ForkJoinPool#submit(ForkJoinTask)}. Because these are JDK classes,
 * the agent retransforms them via the bootstrap instrumentation channel.
 *
 * <p><strong>Thread parking mechanics.</strong> {@code Thread.sleep} is used for the delay.
 * The submitting thread holds no executor-internal locks during the sleep (the lock is only
 * acquired inside the queue's {@code offer} or {@code put} call, which happens after the sleep).
 * If the submitting thread is a virtual thread (Java 21+), the sleep unmounts it from the carrier,
 * allowing other virtual threads to run during the delay.
 *
 * <p><strong>Interaction with rejection policies.</strong> If the executor uses a bounded queue
 * and the {@code RejectedExecutionHandler} is configured to block (e.g., a caller-runs policy),
 * the delay compounds with any existing back-pressure: the submitting thread sleeps during the
 * chaos delay, then potentially blocks again waiting for queue space.
 *
 * <p><strong>Cascading effects.</strong> In request-handling architectures where the request
 * thread submits tasks and then waits for their futures, the delay inflates end-to-end request
 * latency by at least {@link #delayMs} ms per submission. With multiple submissions per request,
 * the total latency impact multiplies.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorSubmitReject} throws
 * {@code RejectedExecutionException} instead of delaying. {@link ChaosExecutorSubmitGate} blocks
 * the submission thread until the test releases it explicitly, enabling precise timing control.
 * {@link ChaosExecutorWorkerRunDelay} targets the worker thread at execution time, not the
 * submitter at submission time.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosExecutorSubmitDelay(delayMs = 200, maxDelayMs = 500)
 * class SlowSubmissionTest {
 *
 *   @Test
 *   void requestLatencyIncreasesDuringSlowSubmission(AppConnectionInfo info) throws Exception {
 *     long start = System.currentTimeMillis();
 *     client.triggerBackgroundTask(info).get(5, TimeUnit.SECONDS);
 *     long elapsed = System.currentTimeMillis() - start;
 *     // at least one submit delay was injected
 *     assertThat(elapsed).isGreaterThanOrEqualTo(200L);
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
 * @see com.macstab.chaos.jvm.api.OperationType#EXECUTOR_SUBMIT
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosExecutorSubmitReject
 * @see ChaosExecutorSubmitGate
 * @see ChaosExecutorWorkerRunDelay
 */
@Repeatable(ChaosExecutorSubmitDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.EXECUTOR_SUBMIT)
public @interface ChaosExecutorSubmitDelay {

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
   * @ChaosExecutorSubmitDelay(id = "primary",  probability = 0.001)
   * @ChaosExecutorSubmitDelay(id = "replica",  probability = 0.01)
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
    ChaosExecutorSubmitDelay[] value();
  }
}
