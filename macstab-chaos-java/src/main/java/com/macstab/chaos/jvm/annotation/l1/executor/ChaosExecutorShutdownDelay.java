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
 * {@link java.util.concurrent.ExecutorService#shutdown shutdown()} and
 * {@link java.util.concurrent.ExecutorService#shutdownNow shutdownNow()} call, stretching the
 * time window between when the application requests a shutdown and when the executor actually stops
 * accepting new tasks.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * EXECUTOR_SHUTDOWN} operation with the {@code delay} effect. It intercepts every call to
 * {@code shutdown()} and {@code shutdownNow()} on {@code ExecutorService} implementations in the
 * container JVM and parks the calling thread for the configured duration before allowing the
 * shutdown to proceed. After the sleep, the executor transitions normally to the shutdown state:
 * no new tasks are accepted, and (for {@code shutdownNow()}) queued tasks are returned as a list.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code ExecutorService.shutdown} and
 * {@code ExecutorService.shutdownNow}. When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the calling thread before the executor's internal shutdown
 *       state transition.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code
 *       [delayMs, maxDelayMs]}), parking the calling thread.
 *   <li>After the sleep, the original shutdown body executes: the executor's state transitions to
 *       {@code SHUTDOWN} or {@code STOP} and new submissions are rejected.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code executor.shutdown()} returns only after the sleep — graceful shutdown takes at
 *       least {@link #delayMs} ms longer than expected.
 *   <li>The executor remains in the {@code RUNNING} state (accepting submissions) for the duration
 *       of the sleep, widening the window during which shutdown-unaware threads may continue to
 *       submit tasks.
 *   <li>Orchestration code that times out waiting for shutdown confirmation may trigger a force
 *       stop before the graceful shutdown has a chance to drain the queue.
 *   <li>{@code awaitTermination} calls made after the delayed shutdown will see the full expected
 *       drain time plus the injected delay.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a container orchestrator sends SIGTERM and expects
 * the application to drain in-flight requests within 5 seconds; a slow shutdown transition (caused
 * by lock contention or deregistration from a service registry) pushes the actual drain window
 * past the deadline, causing the orchestrator to SIGKILL the process mid-request.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets
 * {@code java.util.concurrent.ExecutorService#shutdown()} and
 * {@code java.util.concurrent.ExecutorService#shutdownNow()} via Byte Buddy retransformation.
 * Concrete implementations ({@code ThreadPoolExecutor}, {@code ScheduledThreadPoolExecutor}) are
 * also covered because they inherit or override these methods from the abstract base class.
 *
 * <p><strong>RUNNING state extension.</strong> During the sleep, the executor is still in the
 * {@code RUNNING} state. Any thread that calls {@code executor.submit()} during this window will
 * succeed — the task will be accepted and potentially run. This is different from what happens
 * after the actual shutdown, where submissions throw {@code RejectedExecutionException}. Tests
 * that check for rejection must account for this window.
 *
 * <p><strong>Interaction with awaitTermination.</strong> The total time from the application
 * calling {@code shutdown()} to the executor being fully terminated is the sum of: the injected
 * delay, the time for running tasks to finish, and the time {@code awaitTermination} blocks. If
 * the application calls {@code awaitTermination(5, SECONDS)} immediately after the delayed
 * {@code shutdown()}, the awaitTermination budget begins after the sleep.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorShutdownReject} throws an
 * exception instead of delaying — the executor never shuts down. This annotation preserves the
 * shutdown but stretches its latency. Combine with {@link ChaosExecutorAwaitTerminationDelay} to
 * simulate both a slow shutdown initiation and a slow drain.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosExecutorShutdownDelay(delayMs = 2000)
 * class SlowShutdownTest {
 *
 *   @Test
 *   void orchestratorTimesOutDuringSlowShutdown(AppConnectionInfo info) throws Exception {
 *     long start = System.currentTimeMillis();
 *     client.triggerGracefulShutdown(info);
 *     long elapsed = System.currentTimeMillis() - start;
 *     assertThat(elapsed).isGreaterThanOrEqualTo(2000L);
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
 * @see com.macstab.chaos.jvm.api.OperationType#EXECUTOR_SHUTDOWN
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosExecutorShutdownReject
 * @see ChaosExecutorAwaitTerminationDelay
 */
@Repeatable(ChaosExecutorShutdownDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.EXECUTOR_SHUTDOWN)
public @interface ChaosExecutorShutdownDelay {

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
   * @ChaosExecutorShutdownDelay(id = "primary",  probability = 0.001)
   * @ChaosExecutorShutdownDelay(id = "replica",  probability = 0.01)
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
    ChaosExecutorShutdownDelay[] value();
  }
}
