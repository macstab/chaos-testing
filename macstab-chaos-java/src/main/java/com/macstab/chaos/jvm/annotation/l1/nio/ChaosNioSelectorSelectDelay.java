/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.nio;

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
 * Intercepts {@code Selector.select()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the selector polls the OS for I/O readiness, inflating the total time between
 * consecutive event-loop iterations and simulating a sluggish or overloaded NIO event loop in
 * Netty, Undertow, or any framework that drives channels via a {@code java.nio.Selector}.
 *
 * <h2>What this annotation is</h2>
 *
 * A JVM agent L1 chaos primitive — one typed annotation per (selector family, operation type,
 * effect) tuple. It is declared on a test class or method alongside a container annotation and
 * activates for the lifetime of the test class ({@code beforeAll} / {@code afterAll}) or a single
 * test method ({@code beforeEach} / {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>Before every call to {@code java.nio.channels.Selector#select()} or {@code
 *       Selector#select(long)} inside the target container's JVM, the chaos agent intercepts the
 *       calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code select()} executes normally, blocking until at
 *       least one channel is ready or the specified timeout expires.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every event-loop iteration is delayed by at least {@link #delayMs()} ms; all I/O operations
 *       — reads, writes, accepts, connects — are deferred; assert that the application's response
 *       latency at the 99th percentile increases by approximately the configured delay and that
 *       latency SLO alerting fires.
 *   <li>Netty's {@code DefaultEventLoop.runAllTasks()} processes scheduled tasks after each {@code
 *       select()}; with a delayed select, scheduled timers (e.g. heartbeat sends, {@code
 *       IdleStateHandler} checks) fire late; assert that the application tolerates over-due timer
 *       execution without cascading disconnections.
 *   <li>The selector delay stacks with the actual I/O operation time: a 100 ms select delay plus a
 *       50 ms read delay means each read takes 150 ms from the kernel's perspective; assert that
 *       the application's end-to-end timeout is set higher than the sum of all injected delays.
 *   <li><strong>Production failure mode:</strong> a CPU steal spike on a cloud VM (due to a noisy
 *       neighbour) reduces the event loop's share of CPU time; each {@code select()} call blocks
 *       longer than intended because the VM scheduler does not wake the thread on time; from the
 *       application's perspective, every I/O event takes longer to process; response latency
 *       increases across all connections simultaneously, making the failure appear to be a network
 *       problem rather than a CPU scheduling problem.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.SelectorImpl#select(long)}, the JDK's internal
 * implementation of the {@code Selector} class. On Linux, {@code SelectorImpl} delegates to {@code
 * EPollSelectorImpl}, which calls {@code epoll_wait(2)} with the specified timeout. On macOS, it
 * delegates to {@code KQueueSelectorImpl} and {@code kevent(2)}. The chaos delay fires before the
 * OS-level syscall, adding to the time before the event loop knows which channels are ready.
 *
 * <p>Netty's {@code NioEventLoop} drives its select loop via {@code select(timeoutMillis)} where
 * the timeout is calculated from the next scheduled task deadline. The chaos delay lengthens the
 * effective timeout as seen by the event loop, causing scheduled tasks to fire up to {@code
 * delayMs} milliseconds late. If the delay exceeds the next task deadline, the task runs
 * immediately after select completes but is already past its scheduled time, simulating a
 * persistently overloaded event loop.
 *
 * <p>Netty's selector rebuild logic (the JDK epoll bug workaround in {@code
 * NioEventLoop.rebuildSelector()}) counts the number of consecutive spurious wakeups. The select
 * delay causes legitimate wakeups to be slow, not spurious; the rebuild logic does not trigger.
 * This distinguishes a delay from a spurious wakeup — see {@link
 * ChaosNioSelectorSelectSpuriousWakeup} for the spurious variant.
 *
 * <p>The delay affects all channels registered on the same selector, not just a specific one. In
 * Netty's default configuration each worker event loop manages one selector; all channels assigned
 * to that worker are affected uniformly. Combining multiple worker threads means only the channels
 * assigned to the delayed selector's worker thread experience the fault.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioSelectorSelectDelay(delayMs = 200)
 * class EventLoopLatencyTest {
 *   @Test
 *   void p99LatencyExceedsThresholdAndSloAlertFires(ConnectionInfo info) {
 *     // assert that all channels on the affected worker show elevated latency
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the translator
 *       class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosNioSelectorSelectSpuriousWakeup
 * @see ChaosNioChannelReadDelay
 * @see ChaosNioChannelConnectDelay
 */
@Repeatable(ChaosNioSelectorSelectDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_SELECTOR_SELECT)
public @interface ChaosNioSelectorSelectDelay {

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

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosNioSelectorSelectDelay[] value();
  }
}
