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
 * Intercepts every {@link java.util.concurrent.locks.LockSupport#park(Object)} call and holds the
 * calling thread on a test-controlled latch indefinitely — every AQS lock acquisition,
 * {@code CompletableFuture.get()}, and {@code BlockingQueue.take()} stalls until the test releases
 * the gate or {@code maxBlockMs} elapses.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code MONITOR} selector family with the {@code gate}
 * effect applied to the {@code THREAD_PARK} operation. It intercepts
 * {@code LockSupport.park(Object)} — the single primitive underlying all JUC concurrency — and
 * parks the calling thread on an agent-managed latch that the test controls externally. The
 * annotation is declared on the test class or method alongside a container annotation and is active
 * for the lifetime of the annotated scope.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code LockSupport.park(Object)} and
 * {@code LockSupport.parkNanos(Object, long)}. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the native park stub executes.
 *   <li>The gate effect parks the current thread on the agent's internal gate latch
 *       ({@code LockSupport.park(gateObject)}).
 *   <li>The thread remains parked until the test calls the gate release API, or until the
 *       {@code maxBlockMs} safety timeout fires — whichever comes first.
 *   <li>After gate release, the real {@code LockSupport.park} or {@code parkNanos} executes with
 *       the original arguments, allowing the JUC operation to proceed normally.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>All threads that call {@code LockSupport.park} are stalled — assert that
 *       {@code future.get(500, MILLISECONDS)} throws {@code TimeoutException} while the gate is
 *       held.
 *   <li>Thread dumps show application threads in {@code WAITING (parking)} state with the gate
 *       object as the blocker — assert via {@code ThreadMXBean.getThreadInfo(...).getBlockedCount()}.
 *   <li>After gate release, all parked threads unblock and their operations complete — assert
 *       successful completion within a short window after release.
 *   <li>AQS-based operations that have a timed variant (e.g. {@code tryLock(timeout)}) exhaust
 *       their timeout budget while the gate is held; assert they return {@code false} without
 *       needing the gate to be released.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a global JVM pause caused by a
 * long GC cycle or Kubernetes node pressure freeze — all application threads that attempt to
 * acquire locks or await futures stop making progress simultaneously, causing HTTP request queues
 * to fill, health-check endpoints to time out, and the orchestrator to restart the pod before
 * the freeze ends.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> {@code LockSupport.park} is the single choke point for
 * all JUC blocking. The gate interceptor replaces the original park with a park on the gate's
 * internal latch object. This means the thread's blocker object (visible in thread dumps as the
 * {@code "waiting on"} address) is the gate latch rather than the original AQS node or
 * {@code CompletableFuture} — diagnostics must account for this substitution.
 *
 * <p><strong>Gate release broadcast.</strong> The test calls the gate release API (over the
 * agent's HTTP management endpoint). The agent iterates all threads currently parked on the gate
 * latch and calls {@code LockSupport.unpark} on each one. Unparks are broadcast — all waiting
 * threads are released simultaneously, unlike a real monitor where threads are released one at a
 * time. After release, each thread re-enters the real {@code LockSupport.park}, which may itself
 * block if the AQS predecessor has not yet released the lock.
 *
 * <p><strong>Distinction from {@code ChaosThreadParkDelay}.</strong> The delay effect adds a
 * fixed-duration pre-park and then continues normally. The gate effect holds threads indefinitely
 * until an external trigger. Use the gate to take a point-in-time snapshot of the system's state
 * (all threads blocked), then assert invariants, then release and verify recovery.
 *
 * <p><strong>Timed-park override.</strong> {@code parkNanos(blocker, nanos)} is also intercepted.
 * The gate substitutes an untimed park on the gate latch; the original {@code nanos} deadline is
 * discarded during the gate hold. After the gate releases, the original timed park is invoked —
 * but if the deadline has already elapsed, the timed park returns immediately (the thread observes
 * a spurious wakeup from its AQS perspective).
 *
 * <p><strong>Impact on virtual threads.</strong> Virtual threads park by yielding their carrier.
 * Gate-parking a virtual thread holds the carrier occupied (pinned, if the virtual thread is
 * inside a {@code synchronized} block) until the gate releases. With many virtual threads all
 * hitting the gate simultaneously, the carrier pool can be fully occupied by pinned virtual
 * threads, blocking all other virtual-thread scheduling.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadParkGate(maxBlockMs = 10_000)
 * class ParkGateTest {
 *
 *   @Test
 *   void systemHaltsWhileGateIsHeldThenRecovers(AppConnectionInfo info, GateHandle gate)
 *       throws Exception {
 *     CompletableFuture<String> response = client.fetchAsync(info);
 *     assertThatThrownBy(() -> response.get(500, TimeUnit.MILLISECONDS))
 *         .isInstanceOf(TimeoutException.class);
 *     gate.release();
 *     assertThat(response.get(3, TimeUnit.SECONDS)).isNotNull();
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
 * @see ChaosThreadParkDelay
 * @see ChaosThreadParkSpuriousWakeup
 * @see ChaosMonitorEnterGate
 */
@Repeatable(ChaosThreadParkGate.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.GateTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.MONITOR,
    operationType = OperationType.THREAD_PARK)
public @interface ChaosThreadParkGate {

  /**
   * @return maximum block duration in milliseconds
   */
  long maxBlockMs() default 30_000L;

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
   * @ChaosThreadParkGate(id = "primary",  probability = 0.001)
   * @ChaosThreadParkGate(id = "replica",  probability = 0.01)
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
    ChaosThreadParkGate[] value();
  }
}
