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
 * Silently skips every scheduled task invocation when the {@code ScheduledThreadPoolExecutor}
 * dequeues a ready task — the task is dequeued and its future is marked done, but the wrapped
 * {@code Runnable} or {@code Callable} is never called.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code SCHEDULING} selector family with the {@code
 * suppress} effect applied to the {@code SCHEDULE_TICK} operation. It intercepts the tick path —
 * the moment the pool worker is about to invoke the dequeued task — and discards the execution call
 * without running the application's task body. The annotation is declared on the test class or
 * method alongside a container annotation and is active for the lifetime of the annotated scope
 * (class-scope: {@code beforeAll} to {@code afterAll}; method-scope: {@code beforeEach} to {@code
 * afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on the internal task-execution path of {@code
 * ScheduledThreadPoolExecutor}. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured after the task has been dequeued from the {@code DelayedWorkQueue}
 *       but before the task's {@code run()} method is invoked.
 *   <li>The suppress effect skips the {@code run()} call entirely — the application's {@code
 *       Runnable} or {@code Callable} body does not execute.
 *   <li>The {@code ScheduledFutureTask}'s internal state is completed (the future's result or
 *       cancellation is set), so {@code ScheduledFuture.isDone()} returns {@code true} with a
 *       {@code null} result for {@code Runnable} tasks, and callers of {@code future.get()}
 *       unblock.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>A counter or latch inside the scheduled {@code Runnable} is never incremented or counted
 *       down — assert that it remains at zero after waiting longer than the task's scheduled delay.
 *   <li>{@code ScheduledFuture.isDone()} returns {@code true} and {@code get()} returns {@code
 *       null} — the future completes without the task body having run; assert both.
 *   <li>Periodic tasks ({@code scheduleAtFixedRate}) do not re-schedule their next execution
 *       because re-scheduling is triggered from within the task body — assert that the scheduler's
 *       queue does not refill after the first suppressed tick.
 *   <li>Side effects that the task is responsible for (metric flushing, cache eviction, lease
 *       renewal) do not occur — assert via their observable consequences (e.g. stale metric, full
 *       cache, expired lease).
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a JVM's scheduled-task thread pool
 * that is misconfigured with zero core threads, causing all enqueued tasks to be dequeued by a
 * worker that is then immediately terminated before running the task — a heartbeat task is
 * perpetually dequeued and discarded, causing the application's leader-election TTL to expire and
 * the node to lose its role without any error in the application log.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The tick interception point is inside {@code
 * ScheduledFutureTask#run()}, at the line that delegates to the wrapped delegate (either the user's
 * {@code Runnable.run()} or {@code Callable.call()}). The suppress effect returns before that line.
 * The {@code ScheduledFutureTask}'s own post-run bookkeeping (setting the result, completing the
 * future) executes normally — only the user's code is skipped.
 *
 * <p><strong>Future completion semantics.</strong> For one-shot tasks, the future is completed with
 * a {@code null} result (for {@code Runnable}) or the result of a zero-argument supplier (for
 * {@code Callable}). Because the suppress skips the user's {@code Callable.call()}, the result is
 * {@code null} — callers awaiting the result via {@code future.get()} receive {@code null} rather
 * than the expected computed value. Callers that do not tolerate {@code null} results will fail
 * with a {@code NullPointerException}.
 *
 * <p><strong>Periodic task re-scheduling.</strong> For {@code scheduleAtFixedRate} and {@code
 * scheduleWithFixedDelay}, the next tick is scheduled from within the task's {@code run()} method
 * (the outer wrapper, not the user's body). Because the outer wrapper's re-scheduling logic
 * executes before the suppress takes effect on the inner delegate call, the next tick <em>is</em>
 * re-scheduled even when the user's body is suppressed. This means periodic tasks continue to fire
 * (and be suppressed) on every tick — assert that no tick body executes over an extended window.
 *
 * <p><strong>Distinction from {@code ChaosScheduleSubmitSuppress}.</strong> The submit suppress
 * prevents the task from ever entering the queue. The tick suppress allows the task to enter the
 * queue and be dequeued by the scheduler, but prevents its body from running. Use tick suppress to
 * test what happens when the scheduler "fires" but the task body silently produces no work —
 * distinct from a scenario where the task is never registered at all.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosScheduleTickSuppress
 * class ScheduleTickSuppressTest {
 *
 *   @Test
 *   void cacheNeverEvictedWhenTickIsSuppressed(AppConnectionInfo info) throws Exception {
 *     // fill cache beyond eviction threshold
 *     client.fillCache(info, 1000);
 *     Thread.sleep(2_000); // allow time for eviction task tick to fire and be suppressed
 *     assertThat(client.getCacheSize(info)).isGreaterThan(900);
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
 * @see com.macstab.chaos.jvm.api.OperationType#SCHEDULE_TICK
 * @see com.macstab.chaos.jvm.api.ChaosSelector#scheduling(java.util.Set)
 * @see ChaosScheduleTickDelay
 * @see ChaosScheduleSubmitSuppress
 */
@Repeatable(ChaosScheduleTickSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.SCHEDULING,
    operationType = OperationType.SCHEDULE_TICK)
public @interface ChaosScheduleTickSuppress {

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
   * @ChaosScheduleTickSuppress(id = "primary",  probability = 0.001)
   * @ChaosScheduleTickSuppress(id = "replica",  probability = 0.01)
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
    ChaosScheduleTickSuppress[] value();
  }
}
