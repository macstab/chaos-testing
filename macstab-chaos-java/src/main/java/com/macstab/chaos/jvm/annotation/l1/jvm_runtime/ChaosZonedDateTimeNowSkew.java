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
 * Shifts the {@link java.time.ZonedDateTime} returned by {@code ZonedDateTime.now()} by a
 * configurable offset, simulating wall-clock skew for timezone-aware datetime operations.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code ZONED_DATE_TIME_NOW} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code ZonedDateTime.now()} in the target
 *       container's JVM.
 *   <li>The interceptor returns a {@code ZonedDateTime} shifted by {@link #skewMs()} milliseconds
 *       relative to the true zoned system time; the zone ID is preserved.
 *   <li>The {@link #mode()} selects constant ({@code FIXED}), growing ({@code DRIFT}), or pinned
 *       ({@code FREEZE}) skew behaviour.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Multi-region scheduling anomalies.</strong> Applications that compute time windows
 *       across zones using {@code ZonedDateTime} will apply the wrong boundaries; assert that
 *       DST-boundary and cross-zone comparisons remain correct under skew.
 *   <li><strong>Calendar-driven feature flags misfire.</strong> Flags that enable or disable
 *       features between two {@code ZonedDateTime} instants will transition at the wrong real-world
 *       moment; assert that the fallback default behaviour is safe.
 *   <li><strong>Distributed lock expiry wrong.</strong> Optimistic locking records that embed a
 *       {@code ZonedDateTime} expiry will expire early or late depending on skew direction.
 *   <li><strong>Production failure mode:</strong> recurring jobs triggered at a fixed
 *       {@code ZonedDateTime} wall-clock time (e.g. "3:00 AM UTC+1") will either be skipped or
 *       double-fired when the clock is skewed across the trigger boundary.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ZonedDateTime.now()} carries full timezone context — a {@code ZoneId} and the
 * corresponding UTC offset — making it the richest of the java.time "now" methods. Applications
 * that need to reason about DST transitions or to present timestamps to users in their local zone
 * will favour this type. Because it encodes zone information, skewing it also affects derived
 * conversions to {@code Instant} ({@code .toInstant()}) and to epoch milliseconds
 * ({@code .toEpochSecond() * 1000}), providing a single-annotation path to distort all three
 * representations simultaneously.
 *
 * <p>The agent intercepts {@code ZonedDateTime.now(ZoneId)} using Byte Buddy method-entry advice,
 * delegates to the real implementation to obtain the true {@code ZonedDateTime}, and then adjusts
 * the result by adding the skew duration before returning it. The zone ID is not altered, so
 * {@code result.getZone()} will still return the JVM's default zone or the explicitly requested
 * one.
 *
 * <p>Combining this annotation with {@link ChaosInstantNowSkew} allows a test to apply different
 * offsets to the two APIs simultaneously, creating an inconsistency that mimics an application
 * caught mid-migration between the legacy {@code Date}/{@code Calendar} API and the modern
 * java.time stack. Code that converts between the two representations may compute wildly incorrect
 * results when the skews disagree.
 *
 * <p>The {@code DRIFT} mode simulates a clock that is running too fast or too slow relative to the
 * UTC reference — a realistic condition in containerised workloads where the container's clock
 * source is a shared hypervisor timer that was paused during a live-migration window. Over time, a
 * drifting clock will cross any fixed time-based threshold (session expiry, rotating TLS
 * certificate not-before/not-after), making this mode ideal for threshold-crossing chaos tests.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosZonedDateTimeNowSkew(skewMs = -3_600_000, mode = ClockSkewMode.FIXED) // -1 hour
 * class ZonedDateTimeSkewTest {
 *   @Test
 *   void scheduledJobDoesNotDoubleFireAroundDstBoundary(ConnectionInfo info) { ... }
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
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosZonedDateTimeNowSkew.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ClockSkewTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.ZONED_DATE_TIME_NOW)
public @interface ChaosZonedDateTimeNowSkew {

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
   * @ChaosZonedDateTimeNowSkew(id = "primary",  probability = 0.001)
   * @ChaosZonedDateTimeNowSkew(id = "replica",  probability = 0.01)
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
    ChaosZonedDateTimeNowSkew[] value();
  }
}
