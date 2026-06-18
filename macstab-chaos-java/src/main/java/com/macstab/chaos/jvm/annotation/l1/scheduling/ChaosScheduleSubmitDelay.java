/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.scheduling;

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
 * Parks the calling thread inside {@link java.util.concurrent.ScheduledExecutorService#schedule
 * ScheduledExecutorService.schedule()} for the configured number of milliseconds before the task is
 * registered with the scheduler's delay queue — every scheduled task is submitted later than
 * intended and its first execution is correspondingly delayed.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code SCHEDULING} selector family with the {@code
 * delay} effect applied to the {@code SCHEDULE_SUBMIT} operation. It intercepts the submission path
 * of {@link java.util.concurrent.ScheduledExecutorService} — before the {@code Delayed} task entry
 * is added to the executor's internal delay queue — and parks the submitting thread for {@code
 * delayMs}. The annotation is declared on the test class or method alongside a container annotation
 * and is active for the lifetime of the annotated scope (class-scope: {@code beforeAll} to {@code
 * afterAll}; method-scope: {@code beforeEach} to {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code
 * ScheduledThreadPoolExecutor#schedule(Runnable, long, TimeUnit)} and the {@code Callable}
 * overload. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the task's {@code ScheduledFutureTask} wrapper is constructed
 *       and enqueued into the executor's {@code DelayedWorkQueue}.
 *   <li>The delay effect calls {@code LockSupport.parkNanos} on the calling (submitting) thread for
 *       the configured duration in milliseconds.
 *   <li>After the park returns, the task is submitted to the scheduler normally and enters the
 *       delay queue at the current wall-clock time plus the original requested delay.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The {@code ScheduledFuture} returned by {@code schedule()} does not exist in the executor
 *       during the delay window — assert that {@code executor.getQueue().size()} is zero while the
 *       submitting thread is parked.
 *   <li>The task's first execution occurs at {@code (submissionTime + delayMs + requestedDelay)}
 *       rather than {@code (submissionTime + requestedDelay)} — assert via a timestamp captured
 *       inside the task's {@code Runnable}.
 *   <li>Frameworks that submit heartbeat tasks at startup and check for the first heartbeat within
 *       a deadline fail that check — assert that the framework logs a missed-heartbeat warning.
 *   <li>The submitting thread returns from {@code schedule()} at least {@code delayMs} later than
 *       the non-chaos case — assert with a {@code StopWatch} around the submission call.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a GC pause on the submitting thread
 * just as it enters the scheduler — a Spring {@code @Scheduled} task whose registration is delayed
 * by a 2-second stop-the-world GC causes the application's cache-refresh heartbeat to miss its
 * first window, serving stale data until the next tick fires.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> {@code ScheduledThreadPoolExecutor} is a subclass of
 * {@code ThreadPoolExecutor} that stores tasks in a {@code DelayedWorkQueue} — a binary heap
 * ordered by {@code ScheduledFutureTask#compareTo}. The agent intercepts the public {@code
 * schedule} methods; the internal {@code delayedExecute} helper is not targeted directly. The
 * interceptor fires on the submitting thread, not on a pool worker.
 *
 * <p><strong>Delay-queue timing.</strong> The task's trigger time is computed as {@code
 * System.nanoTime() + delay} inside {@code ScheduledFutureTask}'s constructor. Because the
 * pre-submit park fires before the constructor call, the trigger time is computed after the park —
 * the full delay is preserved relative to when submission actually completes, not relative to when
 * the caller invoked {@code schedule()}.
 *
 * <p><strong>Distinction from {@code ChaosScheduleSubmitSuppress}.</strong> The delay effect
 * eventually registers the task; the task will fire at its intended relative delay after the
 * inflated submission time. The suppress effect prevents registration entirely — the task never
 * enters the queue and never executes. Use delay to test temporal sensitivity; use suppress to test
 * missing-task handling.
 *
 * <p><strong>Distinction from {@code ChaosScheduleTickDelay}.</strong> The submit delay fires at
 * submission time and affects when the task is registered. The tick delay fires when the scheduler
 * dequeues a ready task and affects when execution starts. Use submit delay to fault the
 * registration path; use tick delay to fault the execution path.
 *
 * <p><strong>Cascading effects on periodic tasks.</strong> {@code scheduleAtFixedRate} and {@code
 * scheduleWithFixedDelay} submit an initial task and rely on the pool re-submitting subsequent
 * tasks from within the completed task's code. Only the initial submission is delayed; subsequent
 * periodic re-submissions are not affected unless they themselves go through {@code schedule()}
 * again.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosScheduleSubmitDelay(delayMs = 1_000)
 * class ScheduleSubmitDelayTest {
 *
 *   @Test
 *   void heartbeatMissesWindowWhenSubmissionIsDelayed(AppConnectionInfo info) {
 *     assertThatThrownBy(() -> client.awaitHeartbeat(info, Duration.ofMillis(500)))
 *         .isInstanceOf(TimeoutException.class);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Required:</strong>
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
 * @see com.macstab.chaos.jvm.api.OperationType#SCHEDULE_SUBMIT
 * @see com.macstab.chaos.jvm.api.ChaosSelector#scheduling(java.util.Set)
 * @see ChaosScheduleSubmitSuppress
 * @see ChaosScheduleTickDelay
 */
@Repeatable(ChaosScheduleSubmitDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.SCHEDULING,
    operationType = OperationType.SCHEDULE_SUBMIT)
public @interface ChaosScheduleSubmitDelay {

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
   * @ChaosScheduleSubmitDelay(id = "primary",  probability = 0.001)
   * @ChaosScheduleSubmitDelay(id = "replica",  probability = 0.01)
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
    ChaosScheduleSubmitDelay[] value();
  }
}
