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
 * Discards every {@link Thread#sleep(long)} call so that the method returns immediately without the
 * calling thread waiting — code that relies on sleep-based pacing runs at full CPU speed with no
 * artificial pause.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code THREAD} selector family with the {@code
 * suppress} effect applied to the {@code THREAD_SLEEP} operation. It intercepts {@code
 * Thread.sleep(long)} and {@code Thread.sleep(long, int)} and skips the actual sleep, returning
 * control to the caller as if the requested duration had already elapsed. The annotation is
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
 *   <li>The interceptor captures the call before the native sleep stub executes.
 *   <li>The suppress effect skips the real sleep entirely — no native call is made, no park is
 *       issued, the calling thread does not yield.
 *   <li>The method returns {@code void} to the caller immediately, as if the sleep had completed
 *       normally; no {@code InterruptedException} is thrown.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Wall-clock time across a {@code Thread.sleep(1000)} call is negligible (microseconds)
 *       rather than one second — assert that the elapsed time is less than 50 ms.
 *   <li>Retry loops that use sleep-based backoff now spin at full speed between attempts — assert
 *       that the retry count reaches its maximum within a very short wall-clock window.
 *   <li>The thread's interrupt flag is not set and no exception is raised — assert that the
 *       application continues normally rather than propagating a spurious interruption.
 *   <li>Rate-limiting logic that relies on sleep to pace throughput now generates requests at
 *       uncapped speed — assert that a downstream rate limiter is triggered or that the
 *       application's own overload guard activates.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a busy-wait polling loop whose sleep
 * is accidentally set to zero (e.g. after a misconfigured property resolves to "0ms") — the loop
 * spins at 100% CPU, starving other threads and triggering OS-level CPU throttling on the
 * container, degrading all concurrent requests until an operator intervenes.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> {@code Thread.sleep} is a native method; the interceptor
 * wraps it at the Java bytecode level using Byte Buddy's {@code @SuperCall} skip pattern (the super
 * call is simply not issued). Both overloads ({@code sleep(long)} and {@code sleep(long, int)}) are
 * covered. Because the native stub is never called, the JVM's internal per-thread sleep accounting
 * (visible in thread dumps as "TIMED_WAITING") is bypassed — profilers and flight recorder events
 * that rely on that state will not see a sleep event.
 *
 * <p><strong>Interrupt contract.</strong> The suppress effect never throws {@code
 * InterruptedException}. If the thread's interrupt flag is set when {@code sleep} is called, the
 * real {@code Thread.sleep} would clear the flag and throw; suppression skips this clearing,
 * leaving the interrupt flag set in the calling thread. This means interrupt-aware callers that
 * check {@code Thread.interrupted()} after the sleep will see the flag and may behave differently
 * than they would without chaos — this is an intentional deviation that can expose missed interrupt
 * checks.
 *
 * <p><strong>Distinction from {@code ChaosThreadSleepDelay}.</strong> The delay effect makes every
 * sleep longer. The suppress effect makes every sleep instantaneous. Use suppress to test whether
 * the application correctly handles sleeping being skipped (e.g. tight retry loops) or to
 * accelerate test execution by removing artificial sleeps injected by the application under test.
 *
 * <p><strong>Virtual-thread interaction.</strong> Virtual threads in JDK 21+ implement {@code
 * Thread.sleep} via {@code LockSupport.parkNanos}, which yields the carrier. Suppressing the sleep
 * means the carrier is never yielded for the sleep duration — the virtual thread runs continuously,
 * reducing carrier sharing for other virtual threads during that window.
 *
 * <p><strong>Testing fast-path logic.</strong> Application code sometimes has branches that only
 * execute after a long sleep (e.g. "wait 5 minutes for the lease to expire"). Suppressing sleep
 * lets tests reach those branches in milliseconds without resorting to time-manipulation frameworks
 * or mocking, exercising the real application path.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadSleepSuppress
 * class SleepSuppressTest {
 *
 *   @Test
 *   void retryLoopExhaustsAttemptsImmediatelyWhenSleepIsSkipped(AppConnectionInfo info) {
 *     Instant before = Instant.now();
 *     // service normally sleeps 2 s between each of 5 retries
 *     assertThatThrownBy(() -> client.callWithRetry(info))
 *         .isInstanceOf(ServiceUnavailableException.class);
 *     assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(2));
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
 * @see com.macstab.chaos.jvm.api.OperationType#THREAD_SLEEP
 * @see com.macstab.chaos.jvm.api.ChaosSelector#thread(java.util.Set)
 * @see ChaosThreadSleepDelay
 */
@Repeatable(ChaosThreadSleepSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.THREAD,
    operationType = OperationType.THREAD_SLEEP)
public @interface ChaosThreadSleepSuppress {

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
   * @ChaosThreadSleepSuppress(id = "primary",  probability = 0.001)
   * @ChaosThreadSleepSuppress(id = "replica",  probability = 0.01)
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
    ChaosThreadSleepSuppress[] value();
  }
}
