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
 * Parks the thread attempting to enter a {@code synchronized} block for the configured number of
 * milliseconds before it competes for the monitor — every lock acquisition takes at least {@code
 * delayMs} longer than normal, inflating observed lock-contention time.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code MONITOR} selector family with the {@code delay}
 * effect applied to the {@code MONITOR_ENTER} operation. It intercepts the JVM's {@code
 * monitorenter} bytecode (synthesised via a wrapping method injected by Byte Buddy around {@code
 * synchronized} entry points) and artificially inflates the latency of lock acquisition. The
 * annotation is declared on the test class or method alongside a container annotation and is active
 * for the lifetime of the annotated scope (class-scope: {@code beforeAll} to {@code afterAll};
 * method-scope: {@code beforeEach} to {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor that wraps {@code synchronized} method entry
 * and {@code monitorenter} sites. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the thread attempts to acquire the monitor.
 *   <li>The delay effect calls {@code LockSupport.parkNanos} on the current thread for the
 *       configured duration in milliseconds.
 *   <li>After the park returns, the thread proceeds to the real {@code monitorenter} instruction
 *       and acquires the monitor normally (blocking further if another thread holds it).
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Time spent waiting for the lock (as reported by JMX {@code
 *       ThreadMXBean.getThreadInfo(...).getBlockedTime()}) is at least {@code delayMs} per
 *       acquisition — assert via a JMX client.
 *   <li>Lock-protected critical sections complete normally; assert that results are correct to
 *       distinguish from a gate (which holds the lock indefinitely).
 *   <li>Throughput of lock-protected operations decreases proportionally to {@code delayMs} —
 *       assert with a request-rate metric taken over the test window.
 *   <li>Threads waiting for the lock show {@code BLOCKED} state in thread dumps for longer than
 *       expected — assert using a thread-dump capture or JFR event.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a JVM running on a host under
 * OS-level CPU throttling (e.g. a Kubernetes pod hitting its CPU limit) where the kernel's
 * futex-wait latency spikes by hundreds of milliseconds — a connection pool's checkout lock becomes
 * the bottleneck, causing request latency to degrade well beyond SLO thresholds while thread dumps
 * show every request thread {@code BLOCKED} on the pool's internal lock.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The JVM executes {@code monitorenter} natively via the
 * mark-word CAS path (biased locking in JDK 8–14, lightweight locking in JDK 15+, or OS mutex
 * inflation for contended monitors). Byte Buddy cannot intercept a single bytecode instruction
 * directly; instead the agent wraps the entry of each {@code synchronized} method or the enclosing
 * method of each {@code synchronized} block. The delay fires on every such entry, including
 * re-entrant monitor acquisitions where the same thread already holds the monitor.
 *
 * <p><strong>Re-entrancy and nested locks.</strong> Java monitors are re-entrant: a thread holding
 * monitor {@code M} can enter a second {@code synchronized(M)} block without blocking. The delay
 * fires on re-entrant entries too, because the interceptor cannot distinguish a first acquisition
 * from a re-entrant one without inspecting the thread's monitor stack. In code with deeply nested
 * {@code synchronized} blocks the cumulative delay can be much larger than a single {@code
 * delayMs}.
 *
 * <p><strong>AQS-based locks.</strong> This annotation targets intrinsic monitors ({@code
 * synchronized}) only, not {@link java.util.concurrent.locks.ReentrantLock} or other {@link
 * java.util.concurrent.locks.AbstractQueuedSynchronizer} subclasses. Those are intercepted via the
 * {@code THREAD_PARK} operation family, because their blocking path calls {@code LockSupport.park}.
 *
 * <p><strong>Distinction from {@code ChaosMonitorEnterGate}.</strong> The delay effect parks the
 * thread for a fixed duration and then releases it to compete for the monitor. The gate effect
 * holds the thread indefinitely until the test explicitly releases the gate — it models a deadlock
 * or starvation scenario. Use delay for latency testing; use gate for liveness testing.
 *
 * <p><strong>Virtual-thread interaction.</strong> Virtual threads that enter {@code synchronized}
 * blocks pin their carrier thread in JDK 21 (this restriction is lifted in JDK 24+). The delay park
 * fires before the {@code monitorenter}, so the park itself does not pin the carrier; only the
 * subsequent real {@code monitorenter} causes pinning if the monitor is contended.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosMonitorEnterDelay(delayMs = 200)
 * class MonitorDelayTest {
 *
 *   @Test
 *   void connectionPoolCheckoutTimesOutUnderLockDelay(AppConnectionInfo info) {
 *     assertThatThrownBy(() -> client.callWithTimeout(info, 100))
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
 * @see com.macstab.chaos.jvm.api.OperationType#MONITOR_ENTER
 * @see com.macstab.chaos.jvm.api.ChaosSelector#monitor(java.util.Set)
 * @see ChaosMonitorEnterGate
 * @see ChaosThreadParkDelay
 */
@Repeatable(ChaosMonitorEnterDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.MONITOR,
    operationType = OperationType.MONITOR_ENTER)
public @interface ChaosMonitorEnterDelay {

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
   * @ChaosMonitorEnterDelay(id = "primary",  probability = 0.001)
   * @ChaosMonitorEnterDelay(id = "replica",  probability = 0.01)
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
    ChaosMonitorEnterDelay[] value();
  }
}
