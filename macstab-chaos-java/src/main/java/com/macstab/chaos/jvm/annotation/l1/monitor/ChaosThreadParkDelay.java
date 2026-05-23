/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.monitor;

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
 * Adds the configured number of milliseconds of extra parking before every {@link
 * java.util.concurrent.locks.LockSupport#park(Object)} call executes — every AQS-based lock
 * acquisition, {@code CompletableFuture.get()}, and {@code BlockingQueue.take()} blocks for at
 * least {@code delayMs} longer than the requested wait.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code MONITOR} selector family with the {@code delay}
 * effect applied to the {@code THREAD_PARK} operation. It intercepts
 * {@code LockSupport.park(Object)} before the actual park — the primitive blocking call used by
 * all JUC concurrency constructs — and inserts an additional pre-park delay that inflates every
 * wait-based blocking operation. The annotation is declared on the test class or method alongside a
 * container annotation and is active for the lifetime of the annotated scope.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code LockSupport.park(Object)} and
 * {@code LockSupport.parkNanos(Object, long)}. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the native park stub executes.
 *   <li>The delay effect calls an inner {@code LockSupport.parkNanos} for the configured duration
 *       in milliseconds on the current thread.
 *   <li>After the pre-park delay returns, the real {@code LockSupport.park} (or
 *       {@code parkNanos}) is called with the original arguments, blocking normally.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ReentrantLock.tryLock(timeout, unit)} times out earlier than expected because the
 *       pre-park delay consumes part of the timeout budget — assert that tryLock returns
 *       {@code false} when the timeout is shorter than {@code delayMs}.
 *   <li>{@code CompletableFuture.get(1, TimeUnit.SECONDS)} throws {@code TimeoutException} when
 *       the delay exceeds one second even if the future would have completed sooner — assert the
 *       exception.
 *   <li>{@code BlockingQueue.poll(duration, unit)} returns {@code null} prematurely for the same
 *       reason — assert the null return rather than the expected element.
 *   <li>AQS nodes queued in a {@code ReentrantLock}'s CLH queue accumulate; assert that the
 *       {@code ReentrantLock#getQueueLength()} metric spikes during the delay window.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a Kubernetes node experiencing I/O
 * wait spikes that inflate OS futex-wake latency by 300 ms — a connection-pool checkout using
 * {@code ReentrantLock.tryLock(200, MILLISECONDS)} fails for every request, the pool returns
 * {@code null}, and the service logs {@code NullPointerException} until the node's I/O pressure
 * subsides.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> {@code LockSupport.park} is the single choke point for
 * all JUC blocking: {@code ReentrantLock}, {@code Semaphore}, {@code CountDownLatch},
 * {@code CompletableFuture.get()}, and {@code BlockingQueue} all ultimately call it. The agent
 * intercepts this method via the bootstrap class loader instrumentation channel and applies the
 * delay before the native park stub ({@code sun.misc.Unsafe.park}) executes.
 *
 * <p><strong>AQS interaction.</strong> An AQS node is enqueued and its thread is stored in the
 * node before {@code LockSupport.park} is called. The pre-park delay means the node is in the CLH
 * queue but the thread has not yet yielded the CPU — from the AQS owner's perspective, the waiter
 * appears queued immediately but the thread consumes CPU for {@code delayMs} before sleeping.
 * This inflates CPU consumption during contended lock acquisition.
 *
 * <p><strong>Timed-park interaction.</strong> {@code LockSupport.parkNanos(blocker, nanos)} is
 * also intercepted. The pre-delay is applied before the timed park, consuming part of the
 * deadline. If {@code delayMs} exceeds the requested {@code nanos}, the timed park is entered with
 * a negative or zero deadline, causing it to return immediately — from the caller's view, the
 * wait timed out before any real waiting occurred.
 *
 * <p><strong>Distinction from {@code ChaosMonitorEnterDelay}.</strong>
 * {@code ChaosMonitorEnterDelay} targets intrinsic monitors ({@code synchronized}). This
 * annotation targets {@code LockSupport.park}, covering all JUC constructs. Use both together to
 * inflate all locking latency; use only one to isolate intrinsic vs. explicit locking paths.
 *
 * <p><strong>Virtual-thread interaction.</strong> Virtual threads park by yielding their carrier
 * via {@code LockSupport.park}. The pre-delay on a virtual thread causes the carrier to remain
 * occupied for {@code delayMs} before the virtual thread actually yields — temporarily reducing
 * the carrier pool's capacity to schedule other virtual threads.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadParkDelay(delayMs = 300)
 * class ParkDelayTest {
 *
 *   @Test
 *   void lockCheckoutTimesOutWhenParkIsDelayed(AppConnectionInfo info) {
 *     assertThatThrownBy(() -> client.callWithTimeout(info, 200))
 *         .isInstanceOf(TimeoutException.class);
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
 * @see com.macstab.chaos.jvm.api.OperationType#THREAD_PARK
 * @see com.macstab.chaos.jvm.api.ChaosSelector#monitor(java.util.Set)
 * @see ChaosThreadParkGate
 * @see ChaosThreadParkSpuriousWakeup
 * @see ChaosMonitorEnterDelay
 */
@Repeatable(ChaosThreadParkDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.MONITOR,
    operationType = OperationType.THREAD_PARK)
public @interface ChaosThreadParkDelay {

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
   * @ChaosThreadParkDelay(id = "primary",  probability = 0.001)
   * @ChaosThreadParkDelay(id = "replica",  probability = 0.01)
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
    ChaosThreadParkDelay[] value();
  }
}
