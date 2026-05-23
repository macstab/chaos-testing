/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.stressors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Defeats Project Loom's virtual-thread multiplexing by holding carrier threads inside
 * {@code synchronized} blocks for a configurable duration, simulating the carrier-pinning failure
 * mode that prevents virtual threads from yielding to the scheduler.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor spawns {@link #pinnedThreadCount()} virtual threads, each of which enters a
 * {@code synchronized} block and blocks inside it for {@link #pinDurationMs()} milliseconds before
 * releasing the lock and immediately re-entering it, keeping the carrier thread pinned
 * continuously.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent spawns {@link #pinnedThreadCount()} virtual threads on the container's JVM
 *       fork-join carrier pool.</li>
 *   <li>Each virtual thread enters a {@code synchronized (obj) { Thread.sleep(...) }} block.
 *       Because virtual threads cannot unmount from their carrier while holding a monitor lock
 *       (i.e. while inside a {@code synchronized} block or native call), the carrier thread is
 *       "pinned" — it cannot be reused by the scheduler to run other virtual threads.</li>
 *   <li>With {@link #pinnedThreadCount()} carrier threads pinned, the effective parallelism of the
 *       virtual-thread scheduler is reduced by that many. If {@code pinnedThreadCount} equals the
 *       number of carrier threads (typically equal to {@code Runtime.availableProcessors()}), the
 *       virtual-thread pool is fully saturated and new virtual threads cannot be scheduled.</li>
 *   <li>After {@link #pinDurationMs()} milliseconds, each thread releases the lock and
 *       re-enters immediately, maintaining the pinned state continuously until the rule is
 *       removed.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Virtual-thread throughput degradation.</strong> Applications using
 *       {@code Executors.newVirtualThreadPerTaskExecutor()} or structured concurrency will submit
 *       tasks that are queued but cannot execute because carriers are pinned; assert that the
 *       application's request handling degrades gracefully (e.g. returns 503 or queues requests)
 *       rather than blocking indefinitely.
 *   <li><strong>Latency spike on loom-based servers.</strong> Loom-based HTTP servers (Jetty,
 *       Tomcat with virtual threads) assign one virtual thread per request; carrier pinning starves
 *       all pending requests; assert that the server timeout fires before the client times out.
 *   <li><strong>Deadlock-like stall in mixed synchronized/virtual-thread code.</strong>
 *       Application code that uses both virtual threads and {@code synchronized} blocks (e.g.
 *       legacy JDBC drivers, pre-21 Netty) is already partially pinning carriers; this stressor
 *       amplifies that effect to test the worst case.
 *   <li><strong>JFR pin-warning events.</strong> The JVM emits {@code jdk.VirtualThreadPinned}
 *       JFR events when a virtual thread is pinned and the scheduler cannot run another virtual
 *       thread on that carrier; assert that your monitoring pipeline captures these events and
 *       raises an alert.
 *   <li><strong>Production failure mode:</strong> legacy JDBC drivers, some SSL implementations,
 *       and old Netty pipelines internally use {@code synchronized}, causing virtual threads to
 *       pin their carrier on every I/O call. Under high concurrency, all carrier threads become
 *       pinned and new requests queue up until they time out.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Project Loom (JEP 444, Java 21) implements virtual threads as user-mode threads scheduled by
 * a fork-join pool of platform (carrier) threads. The key feature is that a virtual thread can
 * yield its carrier when it blocks on I/O or {@code LockSupport.park()} — the carrier thread is
 * freed to run another virtual thread, enabling M:N multiplexing.
 *
 * <p>There are two situations where a virtual thread cannot yield its carrier: (1) it is inside a
 * {@code synchronized} block or method (which uses JVM monitors, which are tied to the thread
 * identity), and (2) it is inside a native method call. While pinned, the carrier thread is
 * blocked just like a platform thread — no other virtual thread can use it. The JVM detects this
 * and emits {@code jdk.VirtualThreadPinned} JFR events with the duration of the pin.
 *
 * <p>This stressor exploits condition (1): each stressor virtual thread holds a monitor and calls
 * {@code Thread.sleep(pinDurationMs)}, which on a virtual thread in a {@code synchronized} block
 * is not a yield point — the carrier is held for the full sleep duration. When all carriers are
 * pinned this way, the virtual-thread scheduler's queue grows without bound because no carrier is
 * available to run any queued task.
 *
 * <p>The number of carrier threads is controlled by {@code -Djdk.virtualThread.maxPoolSize} (or
 * defaults to {@code Runtime.availableProcessors()}). Setting {@code pinnedThreadCount} equal to
 * or greater than the carrier pool size fully saturates the scheduler. Setting it to a fraction
 * (e.g. half) exercises partial saturation, which is more representative of production conditions
 * where some carriers handle legitimate work while others are pinned by legacy code.
 *
 * <p>Java 24 introduced synchronized-block yielding (JEP 491), which eliminates most carrier
 * pinning. On JVMs that implement JEP 491, this stressor's effect is reduced or eliminated
 * because the {@code synchronized} block no longer prevents unmounting. This stressor is most
 * impactful on Java 21–23 runtimes where carrier pinning is still the default behaviour.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosVirtualThreadCarrierPinning(pinnedThreadCount = 4, pinDurationMs = 200)
 * class CarrierPinningResilienceTest {
 *   @Test
 *   void loomServerQueuesRequestsWhenCarriersSaturated(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an
 *       {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>Chaos agent JAR</strong> accessible at the path configured in
 *       {@code @JvmAgentChaos}.
 *   <li><strong>{@code macstab-chaos-java} on the test classpath</strong> — required for the
 *       translator.
 *   <li><strong>Java 21+ container image</strong> — virtual threads are required; on Java 17 or
 *       earlier there are no carrier threads to pin and this stressor has no effect.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosVirtualThreadCarrierPinning.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.jvm.annotation.l1.translators.VirtualThreadCarrierPinningTranslator")
public @interface ChaosVirtualThreadCarrierPinning {

  /**
   * @return number of carrier threads to pin (> 0)
   */
  int pinnedThreadCount() default 4;

  /**
   * @return per-cycle pin duration in ms
   */
  long pinDurationMs() default 100L;

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
   * @ChaosVirtualThreadCarrierPinning(id = "primary",  probability = 0.001)
   * @ChaosVirtualThreadCarrierPinning(id = "replica",  probability = 0.01)
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
    ChaosVirtualThreadCarrierPinning[] value();
  }
}
