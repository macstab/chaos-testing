/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.nio;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Intercepts {@code Selector.select()} and returns zero immediately — without blocking and without
 * any channel being ready — injecting a spurious wakeup into the NIO event loop and exercising
 * Netty's epoll-bug detection and selector rebuild logic along with any application code that
 * assumes a zero select result means genuine inactivity.
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
 *   <li>Before every call to {@code java.nio.channels.Selector#select()} or
 *       {@code Selector#select(long)} inside the target container's JVM, the chaos agent
 *       intercepts the calling thread.
 *   <li>The agent returns {@code 0} immediately without invoking the real selector; no channel
 *       readiness bits are updated; the selected-key set remains empty.
 *   <li>The event loop interprets zero as "no channels ready" and proceeds to its task-processing
 *       phase; for Netty this means running any queued tasks, then calling {@code select()} again.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The event loop spins calling {@code select()} at near-100% CPU because each call returns
 *       immediately with zero; Netty's epoll-bug counter increments on each spurious wakeup and
 *       after {@code SELECTOR_AUTO_REBUILD_THRESHOLD} (default 512) consecutive spurious wakeups
 *       Netty rebuilds the selector ({@code NioEventLoop.rebuildSelector()}); assert that the
 *       rebuild occurs and that the rebuilt selector is functional.
 *   <li>Actual I/O readiness events queued by the kernel are never seen while the spurious wakeup
 *       is active; pending reads and writes accumulate; assert that the application's response
 *       latency rises monotonically and that the application does not crash but recovers fully
 *       once the fault window closes.
 *   <li>Scheduled tasks in the event loop task queue continue to run (Netty processes tasks after
 *       each select call regardless of zero return); heartbeats and timer-based retries fire
 *       normally; assert that task-driven logic is not affected by the I/O starvation.
 *   <li><strong>Production failure mode:</strong> the JDK epoll selector bug (pre-Java 11 on
 *       Linux) causes the selector to return spurious wakeups indefinitely; without Netty's
 *       selector rebuild workaround the event loop spins at 100% CPU, starving OS threads
 *       scheduled for other processes on the same core; the application remains reachable via
 *       existing connections but throughput drops as the CPU is consumed by the spinning event
 *       loop; the issue appears as a CPU saturation alert with no corresponding increase in
 *       request rate.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.SelectorImpl#select(long)} (and the no-arg
 * {@code select()}) via Byte Buddy. Netty's {@code NioEventLoop.select(long)} drives the event
 * loop and uses a counter ({@code selectCnt}) to track consecutive select calls that return zero.
 * When {@code selectCnt} exceeds {@code SELECTOR_AUTO_REBUILD_THRESHOLD}, Netty calls
 * {@code rebuildSelector0()} which creates a new {@code Selector}, re-registers all channels
 * on it, and replaces the old selector. This is Netty's workaround for the JDK-6403933 epoll
 * spinning bug on Linux.
 *
 * <p>The spurious wakeup effect is distinct from a normal zero return: in non-blocking mode,
 * {@code select(0)} (i.e. {@code selectNow()}) legitimately returns zero when no channels are
 * ready. Netty specifically calls {@code selectNow()} during task-queue-draining phases and
 * correctly handles a zero return from {@code selectNow()} without incrementing the epoll-bug
 * counter. The intercept on the full {@code select(timeout)} variant targets the blocking
 * select call that should only return zero for a genuine epoll wakeup bug.
 *
 * <p>The spurious wakeup causes the event loop to enter its task-processing phase on every
 * loop iteration even when no tasks are queued. Netty's {@code runAllTasks(long timeoutNanos)}
 * returns quickly when the task queue is empty, so the overhead of spurious task processing
 * phases is low; the CPU cost is dominated by the rapid select-return-select cycle rather than
 * task processing.
 *
 * <p>Unlike {@link ChaosNioSelectorSelectDelay}, which slows the event loop while preserving
 * correct I/O readiness detection, this annotation breaks I/O readiness entirely for the duration
 * of the fault: no reads, writes, accepts, or connects are processed. The fault profile is a
 * complete I/O freeze combined with a CPU spike, rather than the gradual latency increase produced
 * by a delay.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioSelectorSelectSpuriousWakeup
 * class EpollBugRebuildTest {
 *   @Test
 *   void nettySelectorRebuildFiresAndIoResumesAfterFault(ConnectionInfo info) {
 *     // assert selector rebuild metric increments
 *     // assert I/O resumes after fault window closes
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the
 *       translator class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosNioSelectorSelectDelay
 * @see ChaosNioChannelAcceptSuppress
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SpuriousWakeupTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_SELECTOR_SELECT)
public @interface ChaosNioSelectorSelectSpuriousWakeup {

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the JVM agent is not active on the container
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
