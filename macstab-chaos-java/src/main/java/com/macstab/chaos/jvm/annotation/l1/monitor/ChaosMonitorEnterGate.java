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
 * Holds every thread attempting to enter a {@code synchronized} block in an indefinite park before
 * the {@code monitorenter} bytecode executes — all threads block at the lock-acquisition site until
 * the test releases the gate or {@code maxBlockMs} elapses.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code MONITOR} selector family with the {@code gate}
 * effect applied to the {@code MONITOR_ENTER} operation. It intercepts the JVM's {@code
 * monitorenter} sites and parks arriving threads on a test-controlled latch until the test releases
 * them, allowing precise control over lock-starvation windows. The annotation is declared on the
 * test class or method alongside a container annotation and is active for the lifetime of the
 * annotated scope (class-scope: {@code beforeAll} to {@code afterAll}; method-scope: {@code
 * beforeEach} to {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor that wraps {@code synchronized} method entry
 * and {@code monitorenter} sites. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the thread attempts to acquire the monitor.
 *   <li>The gate effect calls {@code LockSupport.park(blocker)} on the current thread, blocking it
 *       on the gate's internal {@code CountDownLatch} or {@code Phaser}.
 *   <li>The thread remains parked until: (a) the test calls the gate release API, or (b) the {@code
 *       maxBlockMs} safety timeout elapses (default: 30 000 ms), whichever comes first.
 *   <li>After release, the thread proceeds to the real {@code monitorenter} and acquires the
 *       monitor normally.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>All threads that attempt to enter any {@code synchronized} block are parked — assert that
 *       requests submitted during the gate window do not complete, using a timeout assertion.
 *   <li>Thread dumps taken during the gate window show all application threads in {@code WAITING
 *       (parking)} state at the interceptor frame — assert using {@code
 *       ThreadMXBean.getThreadInfo}.
 *   <li>After gate release, all parked threads compete normally for the real monitor — assert that
 *       eventually all requests complete successfully.
 *   <li>Threads parked beyond {@code maxBlockMs} are released automatically; assert that the
 *       application does not treat the safety release as an error.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a deadlock where every thread in a
 * connection pool's checkout path waits on a lock held by a thread that is itself waiting on the
 * pool — all active threads reach the gate, no thread can proceed, and the application stops
 * serving requests. A watchdog that detects deadlock after 30 seconds would be exercised by the
 * gate's timeout.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> Byte Buddy wraps the entry of each {@code synchronized}
 * method and the enclosing method of each {@code synchronized} block. The gate interceptor calls
 * {@code LockSupport.park(gateObject)} rather than a timed park, so the thread appears as {@code
 * WAITING} (not {@code TIMED_WAITING}) in thread dumps and JFR events — exactly the state a
 * deadlocked thread would show.
 *
 * <p><strong>Gate release mechanism.</strong> The gate is a server-side agent construct exposed via
 * the agent's HTTP management API. The test calls the release endpoint (or waits for {@code
 * maxBlockMs}) to unpark all waiting threads simultaneously via {@code LockSupport.unpark} on each
 * parked thread. The unpark is broadcast — every thread blocked at the gate is released at once,
 * unlike a real monitor where threads are released one at a time as the holder exits.
 *
 * <p><strong>Distinction from {@code ChaosMonitorEnterDelay}.</strong> The delay effect parks for a
 * fixed known duration. The gate effect parks indefinitely and requires an explicit external
 * release signal. Use the gate when the test needs to assert the system's state while all threads
 * are blocked, then trigger the release and assert recovery.
 *
 * <p><strong>AQS-based locks.</strong> This annotation targets only intrinsic monitors ({@code
 * synchronized}). {@link java.util.concurrent.locks.ReentrantLock} and other AQS subclasses are not
 * affected. To gate AQS-based lock acquisition, use {@code @ChaosThreadParkGate} which targets
 * {@code LockSupport.park} — the primitive underlying all AQS blocking.
 *
 * <p><strong>Virtual-thread pinning.</strong> Virtual threads that enter {@code synchronized}
 * blocks pin their carrier in JDK 21. The gate park fires <em>before</em> the {@code monitorenter},
 * so the pre-gate park does not pin the carrier. Once the gate releases and the thread reaches the
 * real {@code monitorenter}, pinning may occur if the monitor is contended.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosMonitorEnterGate(maxBlockMs = 5_000)
 * class MonitorGateTest {
 *
 *   @Test
 *   void allRequestsBlockWhileGateIsHeld(AppConnectionInfo info, GateHandle gate) throws Exception {
 *     CompletableFuture<String> response = client.fetchAsync(info);
 *     // assert request is blocked
 *     assertThatThrownBy(() -> response.get(500, TimeUnit.MILLISECONDS))
 *         .isInstanceOf(TimeoutException.class);
 *     // release gate and assert recovery
 *     gate.release();
 *     assertThat(response.get(2, TimeUnit.SECONDS)).isNotNull();
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
 * @see com.macstab.chaos.jvm.api.OperationType#MONITOR_ENTER
 * @see com.macstab.chaos.jvm.api.ChaosSelector#monitor(java.util.Set)
 * @see ChaosMonitorEnterDelay
 * @see ChaosThreadParkGate
 */
@Repeatable(ChaosMonitorEnterGate.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.GateTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.MONITOR,
    operationType = OperationType.MONITOR_ENTER)
public @interface ChaosMonitorEnterGate {

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
   * @ChaosMonitorEnterGate(id = "primary",  probability = 0.001)
   * @ChaosMonitorEnterGate(id = "replica",  probability = 0.01)
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
    ChaosMonitorEnterGate[] value();
  }
}
