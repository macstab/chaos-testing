/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.jvm_runtime;

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
 * Shifts the value returned by {@code System.nanoTime()} by a configurable nanosecond offset,
 * distorting monotonic elapsed-time measurements inside the target container's JVM.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code SYSTEM_CLOCK_NANOS} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code System.nanoTime()} in the target
 *       container's JVM.
 *   <li>The interceptor adds the equivalent of {@link #skewMs()} milliseconds (expressed in
 *       nanoseconds) to the raw monotonic counter before returning it to the caller.
 *   <li>In {@code DRIFT} mode the offset grows linearly, simulating a clock that is running at a
 *       different frequency than wall time; in {@code FREEZE} mode the value is pinned, making
 *       elapsed-time measurements return zero.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Timeout detection fires too early or too late.</strong> Connection pools, retry
 *       schedulers, and circuit breakers that compute elapsed time with {@code nanoTime} will open
 *       or close their breakers at the wrong moment; assert that timeouts still produce the correct
 *       error type and that retries respect the backoff window.
 *   <li><strong>SLA / latency metrics corrupted.</strong> Histogram-based latency tracking
 *       (Micrometer, Dropwizard Metrics) uses {@code nanoTime} for sample recording; assert that
 *       the application does not report negative latency or overflow counter buckets.
 *   <li><strong>Throughput limiters break.</strong> Rate limiters using token-bucket algorithms
 *       keyed to {@code nanoTime} may grant or deny requests at the wrong rate.
 *   <li><strong>Production failure mode:</strong> a frozen {@code nanoTime} causes keep-alive
 *       threads to believe no time has passed and skip scheduled work indefinitely; a large
 *       positive skew causes aggressive timeouts that cascade into connection storms.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code System.nanoTime()} is a monotonic, high-resolution counter that is not tied to any
 * calendar epoch. Its only guarantee is that successive calls on the same JVM instance are
 * non-decreasing. Unlike {@code System.currentTimeMillis()}, it is not adjusted by NTP and cannot
 * jump backward. This makes it the standard source for elapsed-time measurement, but also means
 * that code using it is not normally expected to encounter clock anomalies.
 *
 * <p>Intercepting {@code System.nanoTime()} requires the same native-method delegation technique as
 * {@code currentTimeMillis()}: the agent installs a Byte Buddy delegation stub in the bootstrap
 * class loader so the JVM routes the call through Java-visible advice, then adds the skew before
 * returning.
 *
 * <p>The key difference between skewing {@code nanoTime} and skewing {@code currentTimeMillis} is
 * the semantic: {@code nanoTime} is used for <em>elapsed time</em> (how long did this operation
 * take?) while {@code currentTimeMillis} is used for <em>absolute time</em> (what time is it?).
 * Skewing {@code nanoTime} breaks duration-based reasoning — timeouts, rate limits, latency
 * histograms — without affecting calendar-based reasoning. Combined, the two annotations can
 * simulate a node whose wall clock has drifted while its monotonic clock has also been distorted,
 * matching the conditions seen after hypervisor live-migration or container suspension.
 *
 * <p>In {@code FREEZE} mode successive calls to {@code nanoTime} return the same value, so any code
 * that loops on {@code nanoTime} for a spin-wait or busy-poll will spin forever, giving the
 * application an opportunity to demonstrate whether it has a hard CPU-spin ceiling.
 *
 * <p>Because the skew applies only inside the container JVM, the test side retains the true
 * monotonic clock, enabling precise measurement of how long the container took to detect an anomaly
 * such as a doubled timeout window.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSystemClockNanosSkew(skewMs = 5_000, mode = ClockSkewMode.FIXED)
 * class NanoTimeSkewTest {
 *   @Test
 *   void circuitBreakerTimeoutStillTrips(ConnectionInfo info) { ... }
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
@Repeatable(ChaosSystemClockNanosSkew.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ClockSkewTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.SYSTEM_CLOCK_NANOS)
public @interface ChaosSystemClockNanosSkew {

  /**
   * @return clock offset in milliseconds; positive = future, negative = past; non-zero
   */
  long skewMs() default -60_000L;

  /**
   * @return how the skew evolves over time (FIXED / DRIFT / FREEZE)
   */
  com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewMode mode() default
      com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewMode.FIXED;

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
   * @ChaosSystemClockNanosSkew(id = "primary",  probability = 0.001)
   * @ChaosSystemClockNanosSkew(id = "replica",  probability = 0.01)
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
    ChaosSystemClockNanosSkew[] value();
  }
}
