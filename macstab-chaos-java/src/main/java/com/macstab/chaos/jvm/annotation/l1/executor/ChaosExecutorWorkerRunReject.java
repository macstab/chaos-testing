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
 * Throws {@link java.util.concurrent.RejectedExecutionException} from every executor worker thread
 * the moment it dequeues a task — the task body is never executed, the associated {@code Future}
 * completes exceptionally, and the worker thread continues to the next task.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * EXECUTOR_WORKER_RUN} operation with the {@code reject} effect. It intercepts the worker thread's
 * task-dispatch entry point and throws {@code RejectedExecutionException} with the configured
 * {@link #message()} before the task body ({@code Runnable.run()} or {@code Callable.call()}) is
 * invoked. The submitter's call to {@code executor.submit()} already returned a {@code Future} at
 * this point — the exception appears to the submitter via {@code Future.get()} as an {@code
 * ExecutionException} wrapping a {@code RejectedExecutionException}.
 *
 * <p>This is distinct from {@link ChaosExecutorSubmitReject}, which rejects at submission time (the
 * caller's {@code submit()} throws immediately and no {@code Future} is returned). Here the {@code
 * Future} is returned normally; only the execution of the task body is rejected.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on the task-execution entry point inside
 * {@code ThreadPoolExecutor}'s {@code runWorker} loop. When the interceptor fires:
 *
 * <ol>
 *   <li>The worker thread has dequeued the task and is about to invoke {@code task.run()}.
 *   <li>The reject effect throws {@code new RejectedExecutionException(message)} from the
 *       interceptor, before the task body begins.
 *   <li>The {@code ThreadPoolExecutor}'s {@code runWorker} loop catches the unchecked exception and
 *       routes it through the {@code afterExecute} hook; the underlying {@code FutureTask} stores
 *       the exception as its cause.
 *   <li>Any caller blocking on {@code future.get()} receives {@code ExecutionException} wrapping
 *       the {@code RejectedExecutionException}.
 *   <li>The worker thread survives the exception and loops back to dequeue the next task.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code future.get()} throws {@link java.util.concurrent.ExecutionException} whose {@code
 *       getCause()} is a {@code RejectedExecutionException} with the configured message.
 *   <li>The task's business logic never runs — no side effects, no database writes, no I/O.
 *   <li>The worker thread is not killed; subsequent tasks (if any) are dequeued and also rejected
 *       as long as the annotation is active.
 *   <li>Throughput drops to zero for all tasks submitted while the annotation is active, because
 *       every dequeued task is immediately rejected by the worker.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a JVM that exhausts its metaspace or native memory
 * mid-task throws an {@code Error} inside the worker thread, causing {@code ThreadPoolExecutor} to
 * kill the worker — this annotation simulates the lighter version where the worker throws a
 * recoverable {@code RuntimeException} and lives on, but the caller's task is still lost.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The interceptor fires at the {@code task.run()} call
 * inside {@code ThreadPoolExecutor.runWorker(Worker)}. This is after the worker has dequeued the
 * task and acquired the worker lock ({@code w.lock()}). The thrown exception is caught by the
 * {@code try/finally} block in {@code runWorker}, which calls {@code afterExecute(task, thrown)}
 * before releasing the lock. Application code that overrides {@code afterExecute} will see the
 * injected exception.
 *
 * <p><strong>FutureTask exception routing.</strong> When {@code FutureTask} wraps the submitted
 * callable, it catches any exception from {@code Callable.call()} and stores it via {@code
 * setException}. Because the interceptor throws before entering {@code FutureTask.run()}, the
 * exception bypasses {@code FutureTask}'s catch block and is instead caught by {@code runWorker}'s
 * catch — the {@code FutureTask} completes exceptionally only if {@code runWorker} propagates the
 * exception back through the worker's exception handler. The exact routing depends on the JDK
 * version and executor configuration; test your target version.
 *
 * <p><strong>Cascading effects.</strong> While the annotation is active, the executor's worker
 * threads are effectively turned into reject-and-continue machines: they drain the queue rapidly
 * (no task body runs) but complete every future with an exception. Any downstream logic that chains
 * on those futures — {@code thenApply}, {@code handle}, etc. — will fire their error paths.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorSubmitReject} rejects
 * before the task enters the queue — no {@code Future} is returned. This annotation rejects after
 * the task is already in the queue and a {@code Future} has been handed to the caller — the caller
 * gets an exceptionally-completed {@code Future} rather than an immediate exception. {@link
 * ChaosExecutorWorkerRunDelay} delays instead of rejecting, allowing the task to eventually run.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosExecutorWorkerRunReject(message = "chaos: worker rejection")
 * class WorkerRejectTest {
 *
 *   @Test
 *   void futureCompletesExceptionallyWhenWorkerRejects(AppConnectionInfo info) {
 *     Future<String> result = client.submitBackgroundTask(info);
 *     assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
 *         .isInstanceOf(ExecutionException.class)
 *         .hasCauseInstanceOf(RejectedExecutionException.class);
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
 * @see ChaosExecutorWorkerRunDelay
 * @see ChaosExecutorSubmitReject
 */
@Repeatable(ChaosExecutorWorkerRunReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.EXECUTOR_WORKER_RUN)
public @interface ChaosExecutorWorkerRunReject {

  /**
   * @return exception message used by the reject effect
   */
  String message() default "rejected by chaos L1";

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
   * @ChaosExecutorWorkerRunReject(id = "primary",  probability = 0.001)
   * @ChaosExecutorWorkerRunReject(id = "replica",  probability = 0.01)
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
    ChaosExecutorWorkerRunReject[] value();
  }
}
