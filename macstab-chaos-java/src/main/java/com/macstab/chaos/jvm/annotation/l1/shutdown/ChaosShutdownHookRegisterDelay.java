/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.shutdown;

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
 * Parks the calling thread inside {@link Runtime#addShutdownHook(Thread)
 * Runtime.addShutdownHook(thread)} for the configured number of milliseconds before the hook thread
 * is registered with the JVM — every shutdown-hook registration takes at least {@code delayMs}
 * longer than normal.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code SHUTDOWN} selector family with the {@code
 * delay} effect applied to the {@code SHUTDOWN_HOOK_REGISTER} operation. It intercepts {@code
 * Runtime.getRuntime().addShutdownHook(Thread)} before the hook thread is stored in the JVM's
 * internal shutdown-hook map and artificially inflates the registration latency. The annotation is
 * declared on the test class or method alongside a container annotation and is active for the
 * lifetime of the annotated scope (class-scope: {@code beforeAll} to {@code afterAll};
 * method-scope: {@code beforeEach} to {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code Runtime#addShutdownHook(Thread)}.
 * When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the hook thread is validated and added to the JVM's internal
 *       {@code ApplicationShutdownHooks.hooks} map.
 *   <li>The delay effect calls {@code LockSupport.parkNanos} on the calling thread for the
 *       configured duration in milliseconds.
 *   <li>After the park returns, {@code addShutdownHook} executes normally and the hook thread is
 *       registered with the JVM.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Wall-clock time for the {@code addShutdownHook} call is at least {@code delayMs} — assert
 *       with a {@code StopWatch} around the registration call site.
 *   <li>Application startup sequences that register many hooks during their initialisation phase
 *       (e.g. Spring's context shutdown hooks, JDBC driver cleanup hooks) are slower by {@code
 *       delayMs} per hook — assert that the startup-ready signal arrives later than the non-chaos
 *       baseline.
 *   <li>The hook is eventually registered normally; assert that the hook thread's {@code run}
 *       method executes when the JVM terminates (by triggering a graceful shutdown in the test).
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> an application framework that
 * registers shutdown hooks lazily during the first request's handling — a GC pause on the
 * initialisation thread delays shutdown-hook registration by several hundred milliseconds, causing
 * the hook to miss registration if the JVM receives a SIGTERM before the registration completes,
 * and the application exits without flushing its write-ahead log.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> {@code Runtime.addShutdownHook} delegates internally to
 * {@code ApplicationShutdownHooks.add(Thread)}, which acquires a class-level lock on {@code
 * ApplicationShutdownHooks} and stores the hook in a {@code IdentityHashMap}. The agent intercepts
 * the public {@code Runtime.addShutdownHook} entry point. The delay fires before the internal lock
 * is acquired — the calling thread parks without holding the hooks lock, so other concurrent hook
 * registrations are not serialised by the delay.
 *
 * <p><strong>JVM shutdown-phase guard.</strong> {@code addShutdownHook} throws {@link
 * IllegalStateException} if the JVM's shutdown sequence has already begun. The delay fires before
 * this check, so if the JVM starts shutting down during the park, the subsequent {@code
 * addShutdownHook} call will throw rather than register the hook — exercising the application's
 * handling of late-registration failures.
 *
 * <p><strong>Distinction from {@code ChaosShutdownHookRegisterReject}.</strong> The delay effect
 * eventually registers the hook. The reject effect throws before registration, so the hook is never
 * stored and never executed on JVM exit. Use delay to test registration-latency tolerance; use
 * reject to test handling of hook-registration failure.
 *
 * <p><strong>Interaction with container lifecycle.</strong> Docker and Kubernetes send SIGTERM to
 * the JVM process and then wait for {@code terminationGracePeriodSeconds} (default: 30 s) before
 * sending SIGKILL. If a shutdown hook is not registered before SIGTERM arrives (because its
 * registration is delayed past the signal time), the hook does not run during the graceful shutdown
 * window — the container exits without executing the hook's cleanup logic.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosShutdownHookRegisterDelay(delayMs = 2_000)
 * class ShutdownHookDelayTest {
 *
 *   @Test
 *   void hookRegistrationDelaysAppStartup(AppConnectionInfo info) {
 *     Instant before = Instant.now();
 *     client.awaitReady(info);
 *     assertThat(Duration.between(before, Instant.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
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
 * @see com.macstab.chaos.jvm.api.OperationType#SHUTDOWN_HOOK_REGISTER
 * @see com.macstab.chaos.jvm.api.ChaosSelector#shutdown(java.util.Set)
 * @see ChaosShutdownHookRegisterReject
 */
@Repeatable(ChaosShutdownHookRegisterDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.SHUTDOWN,
    operationType = OperationType.SHUTDOWN_HOOK_REGISTER)
public @interface ChaosShutdownHookRegisterDelay {

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
   * @ChaosShutdownHookRegisterDelay(id = "primary",  probability = 0.001)
   * @ChaosShutdownHookRegisterDelay(id = "replica",  probability = 0.01)
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
    ChaosShutdownHookRegisterDelay[] value();
  }
}
