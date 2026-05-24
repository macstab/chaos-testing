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
 * Silently discards every {@link java.util.concurrent.ScheduledExecutorService#schedule
 * ScheduledExecutorService.schedule()} call — the task is never added to the scheduler's delay
 * queue, returns a cancelled {@code ScheduledFuture}, and never executes.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code SCHEDULING} selector family with the {@code
 * suppress} effect applied to the {@code SCHEDULE_SUBMIT} operation. It intercepts the submission
 * path of {@link java.util.concurrent.ScheduledExecutorService} and discards the call before the
 * task's {@code ScheduledFutureTask} is constructed or enqueued. The annotation is declared on the
 * test class or method alongside a container annotation and is active for the lifetime of the
 * annotated scope (class-scope: {@code beforeAll} to {@code afterAll}; method-scope: {@code
 * beforeEach} to {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code
 * ScheduledThreadPoolExecutor#schedule(Runnable, long, TimeUnit)} and the {@code Callable}
 * overload. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the task's {@code ScheduledFutureTask} wrapper is constructed.
 *   <li>The suppress effect discards the call — a pre-cancelled {@code ScheduledFuture} is returned
 *       to the caller (or {@code null} depending on the suppress translator's implementation)
 *       without touching the executor's delay queue.
 *   <li>The task's {@code Runnable} or {@code Callable} is never invoked at any future point.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The executor's queue size ({@code executor.getQueue().size()}) does not increase after the
 *       suppressed submit — assert it remains zero.
 *   <li>The returned {@code ScheduledFuture} is either {@code null} or in the cancelled state;
 *       assert {@code future.isCancelled()} returns {@code true}.
 *   <li>No execution timestamp is ever recorded inside the task's {@code Runnable} — assert that a
 *       counter or latch inside the task is never incremented or counted down.
 *   <li>Frameworks that register periodic health-check or cache-refresh tasks at startup and then
 *       rely on those tasks completing stop producing heartbeat signals — assert that the
 *       application's health endpoint returns unhealthy after the expected heartbeat window.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a scheduler whose thread pool is shut
 * down prematurely (e.g. by a misconfigured graceful-shutdown sequence), causing all subsequent
 * {@code schedule()} calls to be silently rejected — a Spring {@code @Scheduled} lease-renewal task
 * is never registered, the lease expires, and the node loses distributed leadership, causing
 * split-brain until the operator restarts the service.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent intercepts {@code
 * ScheduledThreadPoolExecutor#schedule} before the internal {@code delayedExecute} helper is
 * called. {@code delayedExecute} is the method that constructs a {@code ScheduledFutureTask}, sets
 * its trigger time, and calls {@code super.execute} to enqueue it. By intercepting before this
 * point, the suppress effect ensures no internal scheduler state is modified.
 *
 * <p><strong>Return value semantics.</strong> The {@code schedule} methods return {@code
 * ScheduledFuture<?>}. The suppress translator returns a synthetic future that is immediately
 * cancelled ({@code isCancelled() == true}, {@code isDone() == true}). Callers that call {@code
 * get()} on the returned future receive a {@code CancellationException} immediately rather than
 * hanging.
 *
 * <p><strong>Distinction from {@code ChaosScheduleSubmitDelay}.</strong> The delay effect
 * eventually registers the task after a park; the task will execute. The suppress effect prevents
 * registration entirely; the task never executes. Use delay to test temporal sensitivity; use
 * suppress to test missing-task resilience and fallback logic.
 *
 * <p><strong>Distinction from {@code ChaosScheduleTickSuppress}.</strong> The submit suppress
 * prevents the task from ever entering the queue. The tick suppress allows the task to enter the
 * queue but prevents its execution when the scheduler dequeues it. Use submit suppress to test the
 * task-registration path; use tick suppress to test the execution path.
 *
 * <p><strong>Periodic tasks.</strong> For {@code scheduleAtFixedRate} and {@code
 * scheduleWithFixedDelay}, suppressing the initial submission prevents all subsequent executions
 * because periodic re-scheduling only occurs from within a successfully running task. Suppressing
 * the submit therefore silences the entire recurring task, not just the first execution.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosScheduleSubmitSuppress
 * class ScheduleSubmitSuppressTest {
 *
 *   @Test
 *   void cacheRefreshNeverRunsWhenScheduleIsSuppressed(AppConnectionInfo info) throws Exception {
 *     Thread.sleep(2_000); // allow time for the normally-scheduled task to run
 *     // cache should be stale because the refresh task was never registered
 *     assertThat(client.getCacheAge(info)).isGreaterThan(Duration.ofSeconds(1));
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
 * @see ChaosScheduleSubmitDelay
 * @see ChaosScheduleTickSuppress
 */
@Repeatable(ChaosScheduleSubmitSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.SCHEDULING,
    operationType = OperationType.SCHEDULE_SUBMIT)
public @interface ChaosScheduleSubmitSuppress {

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
   * @ChaosScheduleSubmitSuppress(id = "primary",  probability = 0.001)
   * @ChaosScheduleSubmitSuppress(id = "replica",  probability = 0.01)
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
    ChaosScheduleSubmitSuppress[] value();
  }
}
