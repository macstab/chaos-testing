/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.thread;

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
 * Adds the configured number of milliseconds on top of every {@link Thread#sleep(long)} call — the
 * sleeping thread wakes later than the requested duration, pushing every time-based interval wider
 * than the code intended.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code THREAD} selector family with the {@code delay}
 * effect applied to the {@code THREAD_SLEEP} operation. It intercepts {@code Thread.sleep(long)}
 * and {@code Thread.sleep(long, int)} and extends their effective duration by parking the calling
 * thread for an additional {@code delayMs} after the requested sleep completes. The annotation is
 * declared on the test class or method alongside a container annotation and is active for the
 * lifetime of the annotated scope (class-scope: {@code beforeAll} to {@code afterAll};
 * method-scope: {@code beforeEach} to {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code Thread.sleep(long)} and {@code
 * Thread.sleep(long, int)}. When the interceptor fires:
 *
 * <ol>
 *   <li>The real {@code Thread.sleep} call executes for the requested duration.
 *   <li>After the sleep returns (or is interrupted and re-enters), the delay effect calls {@code
 *       LockSupport.parkNanos} on the current thread for the additional configured duration in
 *       milliseconds.
 *   <li>After the additional park returns, control returns to the caller — the caller observes a
 *       total wake-up latency of {@code requestedMs + delayMs}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Wall-clock elapsed time across the sleep call is at least {@code requestedMs + delayMs};
 *       assert with a {@code StopWatch} around the call site or via response-time measurement.
 *   <li>Retry loops with fixed-interval backoff execute at wider intervals — assert that the total
 *       retry cycle time is at least {@code (delayMs * attempts)} longer than without chaos.
 *   <li>Rate-limiting logic that sleeps to enforce token-bucket intervals now enforces a wider
 *       interval — assert that the effective throughput is lower than the configured rate limit.
 *   <li>The sleeping thread still wakes and continues execution; assert that tasks complete
 *       successfully (distinguishing this from a hang or interrupt).
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a kernel thread scheduler under CPU
 * contention that delivers wake-ups tens to hundreds of milliseconds after the requested timeout —
 * a retry loop with a 1-second sleep between attempts stalls for 1.3 seconds per retry, causing
 * dependent downstream services to time out before the retry succeeds, turning a recoverable
 * transient error into a cascading timeout.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> {@code Thread.sleep} is a native method in HotSpot; Byte
 * Buddy wraps it at the Java level rather than rewriting the native stub. Both the one-argument
 * {@code sleep(long)} and two-argument {@code sleep(long, int)} overloads are instrumented. The
 * additional park is applied after the real sleep returns, so the interruption contract is
 * preserved: if the real sleep is interrupted, {@code InterruptedException} propagates to the
 * caller before the additional park fires.
 *
 * <p><strong>Interrupt contract.</strong> If the thread is interrupted during the real sleep, the
 * additional delay does <em>not</em> execute — the {@code InterruptedException} propagates
 * immediately. If the thread is interrupted during the additional park, the park returns early
 * (park is uninterruptible by specification), the interrupt flag is re-set, and the caller receives
 * control with its interrupt flag set. This means that interrupt-aware callers behave correctly
 * under this annotation.
 *
 * <p><strong>Distinction from {@code ChaosThreadSleepSuppress}.</strong> The delay effect keeps the
 * sleep active (in fact, makes it longer). The suppress effect skips {@code Thread.sleep} entirely,
 * causing the calling thread to return immediately regardless of the requested duration. Use delay
 * to test timing tolerance; use suppress to test whether code behaves correctly when sleeps are
 * skipped (e.g. in tight busy-wait loops).
 *
 * <p><strong>Virtual-thread interaction.</strong> Virtual threads use {@code Thread.sleep} and JDK
 * 21+ re-implements it to yield the carrier rather than blocking the OS thread. The interceptor
 * fires regardless of whether the sleeping thread is a virtual thread or a platform thread; the
 * additional park on a virtual thread causes the carrier to be yielded for {@code delayMs}, freeing
 * it for other virtual threads during that window.
 *
 * <p><strong>Cascading effect on scheduled tasks.</strong> Many scheduled-task implementations use
 * a sleep-based heartbeat in their polling loop. Adding 200 ms to each sleep in a 1-second polling
 * loop extends the effective poll period to 1.2 seconds, delaying detection of stale records and
 * causing timeouts in downstream consumers that depend on timely polling.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadSleepDelay(delayMs = 400)
 * class SleepDelayTest {
 *
 *   @Test
 *   void retryLoopExceedsDeadlineUnderSleepJitter(AppConnectionInfo info) {
 *     Instant deadline = Instant.now().plusSeconds(2);
 *     // service performs 3 retries with 500 ms sleep between each
 *     assertThatThrownBy(() -> client.callWithRetry(info))
 *         .isInstanceOf(TimeoutException.class);
 *     assertThat(Instant.now()).isAfter(deadline);
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
 * @see ChaosThreadSleepSuppress
 */
@Repeatable(ChaosThreadSleepDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.THREAD,
    operationType = OperationType.THREAD_SLEEP)
public @interface ChaosThreadSleepDelay {

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
   * @ChaosThreadSleepDelay(id = "primary",  probability = 0.001)
   * @ChaosThreadSleepDelay(id = "replica",  probability = 0.01)
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
    ChaosThreadSleepDelay[] value();
  }
}
