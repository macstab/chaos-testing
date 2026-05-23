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
 * Parks the scheduler worker thread for the configured number of milliseconds when the
 * {@code ScheduledThreadPoolExecutor} dequeues a ready task — every scheduled task starts
 * executing at least {@code delayMs} later than its trigger time.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code SCHEDULING} selector family with the
 * {@code delay} effect applied to the {@code SCHEDULE_TICK} operation. It intercepts the tick
 * path — the moment a pool worker dequeues a {@code ScheduledFutureTask} whose delay has elapsed
 * and is about to invoke it — and parks the worker for {@code delayMs} before calling the task's
 * {@code run} method. The annotation is declared on the test class or method alongside a container
 * annotation and is active for the lifetime of the annotated scope.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on the internal task-execution path of
 * {@code ScheduledThreadPoolExecutor} (specifically the point where the dequeued
 * {@code ScheduledFutureTask} is about to call its wrapped {@code Runnable} or
 * {@code Callable}). When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured after the task has been dequeued from the {@code DelayedWorkQueue}
 *       but before the task's {@code run()} method is invoked.
 *   <li>The delay effect calls {@code LockSupport.parkNanos} on the pool worker thread for the
 *       configured duration.
 *   <li>After the park returns, the task's {@code run()} method executes normally on the same
 *       worker thread.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>A task scheduled with {@code schedule(task, 100, MILLISECONDS)} does not execute until at
 *       least {@code 100 + delayMs} milliseconds after submission — assert via a timestamp
 *       captured inside the task body.
 *   <li>For {@code scheduleAtFixedRate(task, 0, 1000, MILLISECONDS)}, each tick fires at least
 *       {@code delayMs} late; assert that the inter-tick wall-clock interval is at least
 *       {@code 1000 + delayMs} ms.
 *   <li>The worker thread is in {@code TIMED_WAITING} state for the duration of the park — assert
 *       via {@code ThreadMXBean.getThreadInfo}.
 *   <li>Tasks that drive SLA-sensitive side effects (e.g. lease renewal, metric flush) miss their
 *       deadlines — assert that downstream consumers detect the late signal.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a CPU-throttled container where the
 * scheduler worker is preempted by the kernel for hundreds of milliseconds between dequeuing the
 * task and starting its execution — a distributed-lock renewal task fires late, the lock expires
 * while the renewal logic is parked, and a competing node acquires the lock, causing dual writes
 * to a shared resource.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> {@code ScheduledThreadPoolExecutor} delegates task
 * execution to the inner {@code ScheduledFutureTask#run()} method, which calls either
 * {@code callable.call()} or {@code runnable.run()}. The agent instruments this delegation point.
 * The dequeue from {@code DelayedWorkQueue} has already occurred when the interceptor fires —
 * the task is no longer in the queue, so {@code executor.getQueue().size()} decreases before the
 * delay, not after.
 *
 * <p><strong>Worker-thread occupancy.</strong> The park occupies the pool worker thread for
 * {@code delayMs}. If all pool threads are simultaneously in the delay park (e.g. when many tasks
 * become ready at the same tick), no other scheduled tasks can be dequeued during that window —
 * the effective scheduler stalls for the full delay even if some tasks became ready before others.
 *
 * <p><strong>Distinction from {@code ChaosScheduleSubmitDelay}.</strong> The submit delay fires
 * at submission time, before the task is enqueued. The tick delay fires at execution time, after
 * the task is dequeued. The tick delay shifts the task's actual execution later without affecting
 * the task's position in the delay queue or its nominal trigger time.
 *
 * <p><strong>Fixed-rate vs fixed-delay tasks.</strong> For {@code scheduleAtFixedRate}, the next
 * execution is computed from the previous trigger time (not the completion time). Adding a tick
 * delay does not shift the next trigger time — the scheduler will attempt to "catch up" by
 * executing the next tick sooner. For {@code scheduleWithFixedDelay}, the next trigger is computed
 * from the completion time, so adding a tick delay pushes all subsequent ticks later by
 * {@code delayMs} cumulatively.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosScheduleTickDelay(delayMs = 500)
 * class ScheduleTickDelayTest {
 *
 *   @Test
 *   void renewalTaskFiresLateAndLockExpires(AppConnectionInfo info) {
 *     assertThatThrownBy(() -> client.assertLeadershipMaintained(info, Duration.ofSeconds(1)))
 *         .isInstanceOf(LeadershipLostException.class);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Required:</strong>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#SCHEDULE_TICK
 * @see com.macstab.chaos.jvm.api.ChaosSelector#scheduling(java.util.Set)
 * @see ChaosScheduleTickSuppress
 * @see ChaosScheduleSubmitDelay
 */
@Repeatable(ChaosScheduleTickDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.SCHEDULING,
    operationType = OperationType.SCHEDULE_TICK)
public @interface ChaosScheduleTickDelay {

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
   * @ChaosScheduleTickDelay(id = "primary",  probability = 0.001)
   * @ChaosScheduleTickDelay(id = "replica",  probability = 0.01)
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
    ChaosScheduleTickDelay[] value();
  }
}
