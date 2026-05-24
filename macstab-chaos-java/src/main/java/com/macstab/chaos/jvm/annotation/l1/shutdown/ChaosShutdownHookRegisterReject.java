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
 * Throws the configured exception inside {@link Runtime#addShutdownHook(Thread)
 * Runtime.addShutdownHook(thread)} before the hook is registered — the hook thread is never stored
 * in the JVM's shutdown-hook map and never executes when the JVM terminates.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code SHUTDOWN} selector family with the {@code
 * reject} effect applied to the {@code SHUTDOWN_HOOK_REGISTER} operation. It intercepts {@code
 * Runtime.getRuntime().addShutdownHook(Thread)} and throws before the hook thread is validated or
 * stored, exercising application code that must handle failed hook registration. The annotation is
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
 *   <li>Execution is captured before the hook thread is passed to the internal {@code
 *       ApplicationShutdownHooks.add(Thread)} helper.
 *   <li>The reject effect constructs and throws the configured exception (default message: {@code
 *       "rejected by chaos L1"}) from within the interceptor body.
 *   <li>The exception propagates to the caller of {@code addShutdownHook} — the JVM's hook map is
 *       not modified, and the hook thread remains in the NEW state forever (it is never started by
 *       the JVM's shutdown sequence).
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The caller of {@code addShutdownHook} receives the configured exception — assert that the
 *       application logs a warning or propagates the failure, rather than silently ignoring it.
 *   <li>When the container is stopped gracefully (SIGTERM), the rejected hook's {@code run()}
 *       method is never invoked — assert that the side effects the hook was responsible for
 *       (connection draining, file flushing, metric export) do not occur.
 *   <li>Application frameworks that register shutdown hooks during initialisation (Spring,
 *       Hibernate) throw during context startup — assert that the framework's error handler catches
 *       the exception and either re-throws or falls back gracefully.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a JVM in the middle of a graceful
 * shutdown that has already called {@code System.exit()} and whose shutdown sequence is underway —
 * any subsequent {@code addShutdownHook} calls throw {@link IllegalStateException} because hook
 * registration is closed. A race between application startup and a concurrent shutdown signal can
 * reproduce this: the hook fails to register, the connection pool's drain hook never runs, and
 * in-flight database writes are rolled back by the server's idle-connection timeout rather than by
 * the application's graceful drain.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent intercepts the public {@code
 * Runtime.addShutdownHook(Thread)} method, which is a thin wrapper around {@code
 * ApplicationShutdownHooks.add(Thread)}. The rejection fires before the internal lock on {@code
 * ApplicationShutdownHooks} is acquired — the hook map is untouched and no JVM-internal state is
 * affected by the injected exception.
 *
 * <p><strong>Exception type.</strong> The JVM's own rejection path for {@code addShutdownHook} uses
 * {@link IllegalStateException} (shutdown already in progress) and {@link IllegalArgumentException}
 * (hook already registered). The chaos reject effect throws the configured exception class,
 * defaulting to a {@code RuntimeException} with the configured message. Configure {@code
 * exceptionClassName = "java.lang.IllegalStateException"} to mimic the production failure mode
 * precisely and exercise the application code that catches that specific type.
 *
 * <p><strong>Distinction from {@code ChaosShutdownHookRegisterDelay}.</strong> The delay effect
 * eventually registers the hook after a park; the hook will execute on JVM exit. The reject effect
 * prevents registration entirely; the hook never runs. Use delay to test registration-latency
 * tolerance; use reject to test whether the application detects and reports missing hooks.
 *
 * <p><strong>Multiple hooks and partial rejection.</strong> If the application registers several
 * hooks (e.g. one per framework subsystem), rejecting all of them leaves the JVM with no cleanup
 * logic. Combine this annotation with probability-based filtering (if supported by the {@code
 * probability} attribute) to reject only a fraction of hooks and test partial-cleanup scenarios.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosShutdownHookRegisterReject(message = "shutdown hooks disabled")
 * class ShutdownHookRejectTest {
 *
 *   @Test
 *   void drainDoesNotRunWhenHookIsNotRegistered(AppConnectionInfo info) throws Exception {
 *     // stop the container to trigger shutdown
 *     info.container().stop();
 *     // drain metric should be zero because the hook never ran
 *     assertThat(metrics.getConnectionsDrained()).isEqualTo(0);
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
 * @see ChaosShutdownHookRegisterDelay
 */
@Repeatable(ChaosShutdownHookRegisterReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.SHUTDOWN,
    operationType = OperationType.SHUTDOWN_HOOK_REGISTER)
public @interface ChaosShutdownHookRegisterReject {

  /**
   * @return exception message used by the reject effect
   */
  String message() default "rejected by chaos L1";

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
   * @ChaosShutdownHookRegisterReject(id = "primary",  probability = 0.001)
   * @ChaosShutdownHookRegisterReject(id = "replica",  probability = 0.01)
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
    ChaosShutdownHookRegisterReject[] value();
  }
}
