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
 * Shifts the {@link java.time.Instant} returned by {@code Instant.now()} by a configurable offset,
 * simulating wall-clock skew for code using the java.time API.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code INSTANT_NOW} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code Instant.now()} in the target container's
 *       JVM.
 *   <li>The interceptor returns an {@code Instant} shifted by {@link #skewMs()} milliseconds
 *       relative to the true system time; a negative value moves the instant into the past.
 *   <li>The {@link #mode()} controls whether the skew is constant ({@code FIXED}), grows
 *       monotonically ({@code DRIFT}), or freezes at the activation instant ({@code FREEZE}).
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>OAuth token expiry and JWT {@code exp} checks.</strong> Modern applications
 *       validate JWT {@code exp} claims against {@code Instant.now()}; with a positive skew the
 *       token appears expired before it actually is. Assert that the application refreshes tokens
 *       proactively or returns an appropriate 401.
 *   <li><strong>Event sourcing timestamps corrupted.</strong> Domain events stamped with
 *       {@code Instant.now()} will carry the wrong wall-clock time, potentially violating
 *       causality ordering in projections. Assert that event replay is resilient to non-monotonic
 *       timestamps.
 *   <li><strong>Cache TTL fires at wrong time.</strong> Caffeine and Guava caches keyed to
 *       {@code Instant} for expiry will evict entries early or hold them past their intended
 *       lifetime.
 *   <li><strong>Production failure mode:</strong> in microservices that propagate
 *       {@code Instant.now()} as a distributed timestamp into Kafka headers or tracing spans, skew
 *       causes out-of-order spans in Jaeger/Zipkin and may break downstream systems that expect
 *       monotonically increasing event times.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code Instant.now()} delegates to the system {@code Clock}, which in the default
 * implementation calls {@code System.currentTimeMillis()} or the equivalent platform clock.
 * Intercepting {@code Instant.now()} directly (rather than {@code currentTimeMillis()}) lets the
 * agent target code that uses the java.time API exclusively, without affecting code that still calls
 * the legacy {@code System.currentTimeMillis()} — or vice versa. This granularity is useful when an
 * application mixes the two APIs and the test needs to skew only one layer.
 *
 * <p>The agent installs a Byte Buddy method interceptor on {@code java.time.Instant.now(Clock)} and
 * the no-arg variant {@code Instant.now()}, replacing the returned {@code Instant} with one
 * adjusted by the configured skew. Unlike the native-method interception required for
 * {@code currentTimeMillis()}, {@code Instant.now()} is a pure Java method and can be instrumented
 * with standard method-entry advice without a delegation stub.
 *
 * <p>Combining this annotation with {@link ChaosSystemClockMillisSkew} targeting the same container
 * simulates a node whose entire clock layer is skewed, regardless of which Java API the application
 * uses to read the time. Skewing only {@code Instant.now()} without touching
 * {@code currentTimeMillis()} creates a split-clock scenario that is particularly useful for
 * testing code that mixes legacy and modern time APIs and assumes they agree.
 *
 * <p>The {@code DRIFT} mode is useful for simulating NTP slew: the offset increases linearly at a
 * configurable rate, so the apparent clock gradually diverges from the true wall clock, eventually
 * crossing any fixed threshold (such as a token TTL or a Raft election timeout). Tests can measure
 * at which drift magnitude the application correctly detects the anomaly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosInstantNowSkew(skewMs = -300_000, mode = ClockSkewMode.FIXED)
 * class InstantNowSkewTest {
 *   @Test
 *   void jwtValidationRejectsExpiredTokenGracefully(ConnectionInfo info) { ... }
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
@Repeatable(ChaosInstantNowSkew.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ClockSkewTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.INSTANT_NOW)
public @interface ChaosInstantNowSkew {

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
   * @ChaosInstantNowSkew(id = "primary",  probability = 0.001)
   * @ChaosInstantNowSkew(id = "replica",  probability = 0.01)
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
    ChaosInstantNowSkew[] value();
  }
}
