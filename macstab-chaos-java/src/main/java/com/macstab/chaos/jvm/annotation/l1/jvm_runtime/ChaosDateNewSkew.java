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
 * Shifts the time value of every {@code new java.util.Date()} constructed without explicit
 * arguments by a configurable offset, simulating wall-clock skew in legacy code that uses the
 * pre-java.time date API.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code DATE_NEW} operation — one typed annotation
 * per (selector family, operation type, effect) tuple. Declared on a test class or {@code @Test}
 * method, it is active from {@code beforeAll}/{@code beforeEach} until {@code afterAll}/{@code
 * afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every invocation of the no-arg {@code new java.util.Date()}
 *       constructor in the target container's JVM.
 *   <li>After construction, the interceptor calls {@code Date.setTime()} to shift the date by
 *       {@link #skewMs()} milliseconds; a negative offset moves the date into the past.
 *   <li>The {@link #mode()} selects constant ({@code FIXED}), growing ({@code DRIFT}), or pinned
 *       ({@code FREEZE}) skew behaviour.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Legacy cookie and HTTP header timestamps wrong.</strong> {@code Date} is used by
 *       JAX-RS and Servlet APIs when setting {@code Set-Cookie: Expires=} and {@code
 *       Last-Modified:} headers; a skewed date causes browser caching and CDN TTL calculations to
 *       misfire. Assert that clients that respect these headers behave correctly.
 *   <li><strong>JDBC timestamp inserts incorrect.</strong> Frameworks that bind {@code new Date()}
 *       to a JDBC {@code Timestamp} parameter will insert a wrong value into the database; assert
 *       that date-range queries and audit records remain consistent.
 *   <li><strong>XML and SOAP timestamp fields corrupted.</strong> JAXB serialises {@code
 *       java.util.Date} fields as ISO 8601 strings; assert that downstream consumers validate and
 *       reject out-of-range dates.
 *   <li><strong>Production failure mode:</strong> legacy applications that use {@code new Date()}
 *       for pessimistic locking (e.g. "lock acquired at", "lock expires at = now + N seconds") will
 *       release or retain locks at the wrong wall-clock time, causing phantom locks or premature
 *       lock expiry.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code java.util.Date}'s no-arg constructor is defined as {@code new
 * Date(System.currentTimeMillis())}. The agent instruments the constructor's exit point (after the
 * superclass chain has completed) using a Byte Buddy constructor-exit interceptor and adjusts the
 * internal {@code fastTime} field via {@code Date.setTime()} before the constructor returns. This
 * approach is preferred over intercepting the call to {@code System.currentTimeMillis()} inside the
 * constructor because it leaves the millis call unaffected for other callers that have not opted
 * into clock skew.
 *
 * <p>This annotation is the legacy equivalent of {@link ChaosInstantNowSkew} for code that has not
 * been migrated to java.time. Many enterprise applications contain a mixture of both APIs,
 * particularly in JPA entity listeners or Hibernate types that still materialise {@code
 * java.util.Date} for JDBC compatibility. Applying both this annotation and {@link
 * ChaosInstantNowSkew} with matching offsets provides uniform skew across the entire codebase;
 * applying different offsets creates an inconsistency that can surface bugs in code that converts
 * between the two representations.
 *
 * <p>The {@code DRIFT} mode is useful for simulating the gradual divergence of a clock that is not
 * disciplined by NTP. Because the date's millisecond value is incremented monotonically relative to
 * the drift rate, a date produced at time T will still be older than a date produced at time T+1,
 * but both will be offset from truth by an increasing amount.
 *
 * <p>Caution: the instrumentation applies only to the no-arg constructor. Code that creates a date
 * with an explicit epoch value ({@code new Date(millis)}) is not intercepted by this annotation and
 * will continue to use whatever millisecond value was passed in.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosDateNewSkew(skewMs = -7_200_000) // -2 hours
 * class LegacyDateSkewTest {
 *   @Test
 *   void cookieExpiryHandledCorrectlyUnderClockSkew(ConnectionInfo info) { ... }
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
@Repeatable(ChaosDateNewSkew.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ClockSkewTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.DATE_NEW)
public @interface ChaosDateNewSkew {

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
   * @ChaosDateNewSkew(id = "primary",  probability = 0.001)
   * @ChaosDateNewSkew(id = "replica",  probability = 0.01)
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
    ChaosDateNewSkew[] value();
  }
}
