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
 * Throws {@link java.util.concurrent.RejectedExecutionException} from every
 * {@link java.util.concurrent.ForkJoinPool} worker the moment it begins executing a
 * {@link java.util.concurrent.ForkJoinTask} — the task's compute method never runs, and the task
 * completes exceptionally with the injected exception.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * FORK_JOIN_TASK_RUN} operation with the {@code reject} effect. It intercepts the internal
 * {@code ForkJoinTask.doExec()} entry point and throws {@code RejectedExecutionException} with the
 * configured {@link #message()} before the task's {@code exec()} or {@code compute()} method is
 * called. The exception is stored as the task's exceptional result — any caller blocked on
 * {@code ForkJoinTask.get()} receives {@code ExecutionException} wrapping the injected exception.
 *
 * <p>This is the ForkJoin-specific analogue of {@link ChaosExecutorWorkerRunReject}. For
 * {@code ThreadPoolExecutor}-based pools, use that annotation instead.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code ForkJoinTask.doExec()}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>The interceptor fires on the {@code ForkJoinWorkerThread} that has picked up the task.
 *   <li>The reject effect throws {@code new RejectedExecutionException(message)} before any of
 *       the task's own code runs.
 *   <li>{@code ForkJoinTask}'s internal exception handling catches the exception and stores it as
 *       the task's exceptional result via the task's status CAS.
 *   <li>The worker thread is unharmed and continues processing other tasks from its deque.
 *   <li>Any thread calling {@code task.get()} or {@code task.join()} receives the stored exception
 *       wrapped in {@code ExecutionException}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code task.get()} throws {@link java.util.concurrent.ExecutionException} whose
 *       {@code getCause()} is a {@code RejectedExecutionException} with the configured message.
 *   <li>{@code task.join()} throws {@link java.util.concurrent.CancellationException} or
 *       re-throws the exception directly, depending on how {@code ForkJoinTask} surfaces it.
 *   <li>The task's compute or action method never executes — no side effects occur.
 *   <li>Parallel streams backed by the common pool will throw {@code RuntimeException} wrapping
 *       the injected exception when the stream terminal operation is collected.
 *   <li>All subtasks forked by the failing task are also affected if they are submitted to the
 *       same pool — each subtask independently triggers the interceptor and is also rejected.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a JVM that encounters an out-of-memory condition
 * during a parallel computation has its {@code ForkJoinWorkerThread}s throw {@code OutOfMemoryError}
 * from inside task bodies; caller code that only handles {@code ExecutionException} (not
 * {@code Error}) propagates the failure incorrectly — this annotation injects a recoverable
 * exception version to test the error-propagation path without requiring actual OOM.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code ForkJoinTask.doExec()} — a
 * package-private method in {@code java.util.concurrent.ForkJoinTask}. Retransformation via the
 * bootstrap class loader channel is required. The method is called by
 * {@code ForkJoinWorkerThread.run()} when the worker picks up a task, and also by
 * {@code ForkJoinTask.join()} when a caller thread executes the task inline (helping mode). Both
 * paths are intercepted.
 *
 * <p><strong>Task status CAS.</strong> {@code ForkJoinTask} uses a status field with CAS to record
 * completion and exceptions. When the reject exception is thrown from {@code doExec()}'s body, the
 * exception handling in {@code ForkJoinTask} stores the exception via {@code recordExceptionalCompletion},
 * which CAS-sets the status to {@code EXCEPTIONAL}. This propagates to all tasks that join on this
 * one, making the failure cascade through recursive fork/join decompositions.
 *
 * <p><strong>Helping mode interaction.</strong> In {@code ForkJoinPool}'s helping protocol, a
 * thread that calls {@code join()} on an incomplete task may execute it inline rather than block.
 * The interceptor fires in this case too — the joining thread itself throws the exception. The
 * exception is stored in the task and re-thrown from the {@code join()} call.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosForkJoinTaskRunDelay} delays but
 * allows the task to run. This annotation prevents the task from running at all.
 * {@link ChaosExecutorWorkerRunReject} does the same thing but targets {@code ThreadPoolExecutor}
 * workers — it does not affect {@code ForkJoinPool} tasks. Use this annotation specifically when
 * the application relies on {@code ForkJoinPool}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosForkJoinTaskRunReject(message = "chaos: fork-join task rejected")
 * class ParallelStreamRejectTest {
 *
 *   @Test
 *   void parallelStreamPropagatesExceptionWhenTasksAreRejected(AppConnectionInfo info) {
 *     assertThatThrownBy(() -> client.fetchParallel(info))
 *         .isInstanceOf(RuntimeException.class)
 *         .hasRootCauseInstanceOf(RejectedExecutionException.class);
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
 * @see com.macstab.chaos.jvm.api.OperationType#FORK_JOIN_TASK_RUN
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosForkJoinTaskRunDelay
 * @see ChaosExecutorWorkerRunReject
 */
@Repeatable(ChaosForkJoinTaskRunReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.FORK_JOIN_TASK_RUN)
public @interface ChaosForkJoinTaskRunReject {

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
   * @ChaosForkJoinTaskRunReject(id = "primary",  probability = 0.001)
   * @ChaosForkJoinTaskRunReject(id = "replica",  probability = 0.01)
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
    ChaosForkJoinTaskRunReject[] value();
  }
}
