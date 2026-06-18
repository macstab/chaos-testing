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
 * Shifts the {@link java.time.LocalDateTime} returned by {@code LocalDateTime.now()} by a
 * configurable offset, simulating clock skew for timezone-agnostic datetime operations.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code LOCAL_DATE_TIME_NOW} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code LocalDateTime.now()} in the target
 *       container's JVM.
 *   <li>The interceptor returns a {@code LocalDateTime} shifted by {@link #skewMs()} milliseconds
 *       relative to the true local time; a negative value moves it into the past.
 *   <li>The {@link #mode()} selects constant ({@code FIXED}), growing ({@code DRIFT}), or pinned
 *       ({@code FREEZE}) skew behaviour.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Audit log timestamps wrong.</strong> Entities stamped with {@code
 *       LocalDateTime.now()} at creation or modification time will carry a shifted timestamp that
 *       violates chronological ordering of audit trails; assert that the application validates or
 *       rejects records with future timestamps.
 *   <li><strong>Business-rule windows broken.</strong> Applications that check whether {@code
 *       LocalDateTime.now()} falls within an open/close window (trading hours, maintenance
 *       blackout) will make wrong decisions; assert that boundary conditions are handled
 *       idempotently.
 *   <li><strong>Scheduled-task drift.</strong> In-process schedulers that compare {@code
 *       LocalDateTime.now()} against a next-run time will fire tasks early or skip them.
 *   <li><strong>Production failure mode:</strong> audit logs with future timestamps can violate
 *       GDPR retention constraints enforced by downstream data-lake pipelines that reject records
 *       whose timestamp exceeds the ingestion time by more than a configurable tolerance.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code LocalDateTime.now()} resolves the current date and time in the JVM's default time zone
 * without any offset-from-UTC information, making it the simplest of the java.time "now" methods.
 * Because it discards timezone context, it is commonly used for display, logging, and business
 * rules that are inherently local — but this also means skewing it produces effects that are
 * invisible to any code that uses zone-aware types such as {@code ZonedDateTime} or {@code
 * OffsetDateTime}.
 *
 * <p>The agent intercepts {@code LocalDateTime.now(ZoneId)} (the method ultimately called by all
 * no-arg and single-arg variants) using standard Byte Buddy method-entry advice, replaces the
 * {@code LocalDateTime} result with one adjusted by the skew amount, and returns it to the caller.
 * No native-method delegation is required because {@code LocalDateTime.now()} is a pure Java
 * method.
 *
 * <p>Mixing this annotation with {@link ChaosInstantNowSkew} or {@link ChaosSystemClockMillisSkew}
 * on the same container creates an inconsistency between the different time APIs, which is a
 * realistic scenario on nodes that do not propagate clock corrections uniformly through all layers
 * (e.g. the OS clock is corrected by NTP but the JVM's default {@code Clock} instance is cached and
 * not refreshed). Tests that assert internal consistency between timestamps produced by different
 * APIs will catch this class of bug.
 *
 * <p>The {@code FREEZE} mode is particularly useful for testing date-based pagination or report
 * generation that assumes {@code LocalDateTime.now()} advances between page requests; frozen, the
 * code may produce identical page boundaries on every call or enter an infinite loop.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosLocalDateTimeNowSkew(skewMs = 86_400_000) // +1 day
 * class LocalDateTimeSkewTest {
 *   @Test
 *   void auditLogDoesNotAcceptFutureTimestamps(ConnectionInfo info) { ... }
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
@Repeatable(ChaosLocalDateTimeNowSkew.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ClockSkewTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.LOCAL_DATE_TIME_NOW)
public @interface ChaosLocalDateTimeNowSkew {

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
   * @ChaosLocalDateTimeNowSkew(id = "primary",  probability = 0.001)
   * @ChaosLocalDateTimeNowSkew(id = "replica",  probability = 0.01)
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
    ChaosLocalDateTimeNowSkew[] value();
  }
}
