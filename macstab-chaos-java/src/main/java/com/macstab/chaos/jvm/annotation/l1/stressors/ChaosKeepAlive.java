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
 * Spawns a non-terminating background thread inside the target container's JVM that blocks the JVM
 * from shutting down gracefully if configured as a non-daemon thread, simulating the presence of a
 * misbehaving background task that refuses to stop on SIGTERM.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept a
 * specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor starts a single named thread that parks itself in a tight {@code
 * LockSupport.parkNanos()} loop with a configurable heartbeat interval, ignoring interrupts, and
 * runs until the chaos rule is explicitly removed by the agent.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent creates a single {@code Thread} named {@link #threadName()}, sets its {@link
 *       #daemon()} flag, and starts it.
 *   <li>The thread enters a loop that calls {@code LockSupport.parkNanos(heartbeatMs * 1_000_000)}
 *       and, on each wakeup, checks whether it has been interrupted. If interrupted, it clears the
 *       interrupt flag and parks again — the thread deliberately ignores the standard interruption
 *       protocol.
 *   <li>As long as {@code daemon = false}, the JVM will not exit normally (via {@code
 *       Runtime.halt()} or the completion of all non-daemon threads) until this thread terminates.
 *       The JVM will wait indefinitely during the shutdown sequence, delaying or preventing
 *       graceful termination.
 *   <li>At chaos rule removal, the agent forcibly terminates the thread (using the agent's internal
 *       control channel, not {@code Thread.stop()}), allowing the JVM to proceed with shutdown.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Delayed graceful shutdown.</strong> When the container receives SIGTERM, the JVM
 *       runs shutdown hooks and then waits for all non-daemon threads to terminate. With {@code
 *       daemon = false}, the keep-alive thread blocks this phase indefinitely; Kubernetes will
 *       eventually send SIGKILL after {@code terminationGracePeriodSeconds}; assert that the
 *       application's shutdown hook completes its critical cleanup (flushing buffers, closing
 *       connections) before SIGKILL arrives.
 *   <li><strong>Rolling deployment stall.</strong> A deployment controller that waits for the old
 *       pod to terminate before starting the new one will be blocked; assert that the readiness
 *       probe is removed promptly on SIGTERM so the load balancer stops routing traffic even if the
 *       JVM has not exited.
 *   <li><strong>Shutdown hook ordering.</strong> JVM shutdown hooks are run in parallel with each
 *       other but not in a defined order; the keep-alive thread competes with shutdown hooks for
 *       the JVM's non-daemon thread table; assert that shutdown hooks complete their work
 *       independently of whether non-daemon application threads have terminated.
 *   <li><strong>Prometheus metric scraping during shutdown.</strong> While the JVM is alive but
 *       application threads have stopped, the Prometheus endpoint may still respond (if served by a
 *       daemon thread) or not (if served by a non-daemon thread that has exited); assert that the
 *       scraper's last scrape before the pod is removed from service reflects the correct state.
 *   <li><strong>Production failure mode:</strong> a background task (scheduled job, long-running
 *       async operation, a thread pool that is not shut down in the shutdown hook) holds a
 *       non-daemon thread alive during shutdown, causing the container to stall until SIGKILL —
 *       losing any in-flight state that was not persisted before the kill.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The JVM shutdown sequence begins when either {@code System.exit()} is called, the last
 * non-daemon thread terminates, or an unhandled exception causes the main thread to exit. The
 * sequence consists of three phases: (1) all registered shutdown hooks run concurrently; (2) if
 * {@code runFinalizersOnExit} is enabled (rare and deprecated), all uninvoked finalizers run; (3)
 * {@code Runtime.halt()} is called to stop the VM. A non-daemon thread that is still running at the
 * start of phase 1 keeps the JVM alive until the thread exits — shutdown hooks run, but the JVM
 * waits at the end of phase 1 for all non-daemon threads to finish before calling {@code halt()}.
 *
 * <p>With {@code daemon = true} (the default), the keep-alive thread is a daemon and does not
 * prevent JVM shutdown; the JVM can exit even while the thread is parked. This is the safe default
 * for most test scenarios: it exercises whether the keep-alive thread appears in thread dumps,
 * affects monitoring, or consumes stack resources without affecting shutdown timing. Set {@code
 * daemon = false} explicitly to test shutdown-stall scenarios.
 *
 * <p>The thread ignores {@code Thread.interrupt()} by design, simulating a misbehaving background
 * task that does not honour the standard cooperative shutdown protocol. Application frameworks that
 * send {@code interrupt()} to background threads during shutdown and assume the threads will stop
 * will fail to shut down cleanly in this scenario.
 *
 * <p>The {@link #heartbeatMs()} interval controls how often the thread wakes up and checks for
 * termination signals from the agent's control channel. A shorter interval makes the thread more
 * responsive to rule removal at the cost of slightly higher CPU wakeup frequency; the default 1000
 * ms is sufficient for test scenarios where cleanup latency of up to one second is acceptable.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosKeepAlive(threadName = "chaos-l1-keepalive", daemon = false, heartbeatMs = 500)
 * class ShutdownStallTest {
 *   @Test
 *   void shutdownHookCompletesBeforeSigkillUnderKeepAlive(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an {@code
 *       ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>Chaos agent JAR</strong> accessible at the path configured in
 *       {@code @JvmAgentChaos}.
 *   <li><strong>{@code macstab-chaos-java} on the test classpath</strong> — required for the
 *       translator.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosKeepAlive.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.KeepAliveTranslator")
public @interface ChaosKeepAlive {

  /**
   * @return name of the kept-alive thread (non-blank)
   */
  String threadName() default "chaos-l1-keepalive";

  /**
   * @return whether the thread is a daemon
   */
  boolean daemon() default true;

  /**
   * @return heartbeat interval between park cycles, in ms
   */
  long heartbeatMs() default 1000L;

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
   * @ChaosKeepAlive(id = "primary",  probability = 0.001)
   * @ChaosKeepAlive(id = "replica",  probability = 0.01)
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
    ChaosKeepAlive[] value();
  }
}
