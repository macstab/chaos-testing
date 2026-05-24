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
 * Throws {@link java.util.concurrent.RejectedExecutionException} from every {@link
 * java.util.concurrent.ExecutorService#shutdown shutdown()} and {@link
 * java.util.concurrent.ExecutorService#shutdownNow shutdownNow()} call, preventing the executor
 * from transitioning to the shutdown state — the executor remains {@code RUNNING} and continues to
 * accept new tasks indefinitely.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * EXECUTOR_SHUTDOWN} operation with the {@code reject} effect. It intercepts every shutdown attempt
 * on any {@code ExecutorService} in the container JVM and throws {@code RejectedExecutionException}
 * with the configured {@link #message()} before the executor's internal shutdown state transition
 * executes. The executor stays in the {@code RUNNING} state; worker threads continue processing
 * queued tasks and accepting new submissions as if no shutdown had been requested.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code ExecutorService.shutdown} and {@code
 * ExecutorService.shutdownNow}. When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the calling thread before any state transition in the
 *       executor.
 *   <li>The reject effect throws {@code new RejectedExecutionException(message)} immediately.
 *   <li>The executor's state remains {@code RUNNING}; no shutdown flag is set.
 *   <li>The exception propagates up the call stack of the thread that tried to shut down the
 *       executor — typically a shutdown hook, a lifecycle manager, or a test's {@code afterAll}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code executor.shutdown()} throws {@link java.util.concurrent.RejectedExecutionException}
 *       — the shutdown call fails immediately.
 *   <li>{@code executor.isShutdown()} returns {@code false} — the executor is still running.
 *   <li>{@code executor.isTerminated()} returns {@code false}.
 *   <li>Subsequent {@code executor.submit(task)} calls succeed — the executor keeps accepting work.
 *   <li>Application lifecycle managers that do not handle {@code RejectedExecutionException} from
 *       shutdown will propagate an unhandled exception through their shutdown sequence, potentially
 *       skipping subsequent cleanup steps.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a JVM shutdown hook calls {@code
 * executorService.shutdown()} on a shared executor, but a concurrent thread holds the executor's
 * internal lock (e.g., inside a long-running task that also calls {@code shutdown}), causing the
 * shutdown attempt to fail or deadlock — the executor never drains and the JVM process hangs at
 * exit, eventually killed by the OS after the shutdown timeout.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code
 * java.util.concurrent.ExecutorService#shutdown()} and {@code
 * java.util.concurrent.ExecutorService#shutdownNow()} on all concrete implementations via Byte
 * Buddy retransformation. The exception is thrown before the executor's main lock is acquired, so
 * the executor's internal state is guaranteed to remain unmodified.
 *
 * <p><strong>Resource leak implication.</strong> Because the executor never shuts down, all worker
 * threads remain alive for the container's lifetime. Any tasks that the application submitted
 * expecting them to be the last will continue running. If the application shuts down external
 * resources (database connections, file handles) after calling shutdown but relies on the executor
 * to drain before those resources are released, the still-running worker threads may encounter
 * already-closed resources, producing secondary errors.
 *
 * <p><strong>Interaction with awaitTermination.</strong> Calls to {@code
 * executor.awaitTermination(timeout, unit)} immediately after the rejected shutdown will block
 * until the timeout elapses (since the executor is still running) and return {@code false},
 * signalling to the caller that the executor did not terminate within the budget.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorShutdownDelay} allows the
 * shutdown to succeed, just slowly. This annotation prevents the shutdown from happening at all.
 * Combine with a subsequent test assertion on the application's restart/recovery behavior to verify
 * that the system eventually reaches a consistent state after a failed shutdown attempt.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosExecutorShutdownReject(message = "chaos: shutdown blocked")
 * class ShutdownRejectedTest {
 *
 *   @Test
 *   void lifecycleManagerHandlesShutdownFailure(AppConnectionInfo info) {
 *     // trigger the app's graceful shutdown path
 *     assertThatThrownBy(() -> client.triggerGracefulShutdown(info))
 *         .hasMessageContaining("executor shutdown failed");
 *     // app should still serve requests since the executor keeps running
 *     assertThat(client.healthCheck(info)).isEqualTo(200);
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
 * @see com.macstab.chaos.jvm.api.OperationType#EXECUTOR_SHUTDOWN
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosExecutorShutdownDelay
 * @see ChaosExecutorAwaitTerminationDelay
 */
@Repeatable(ChaosExecutorShutdownReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.EXECUTOR_SHUTDOWN)
public @interface ChaosExecutorShutdownReject {

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
   * @ChaosExecutorShutdownReject(id = "primary",  probability = 0.001)
   * @ChaosExecutorShutdownReject(id = "replica",  probability = 0.01)
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
    ChaosExecutorShutdownReject[] value();
  }
}
