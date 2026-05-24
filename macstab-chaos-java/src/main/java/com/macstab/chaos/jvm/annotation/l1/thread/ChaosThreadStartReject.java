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
 * Throws a {@link java.lang.OutOfMemoryError} (or configured exception) inside {@link
 * Thread#start()} before the native {@code start0()} call executes — the new thread never enters
 * the runnable state and the caller receives an exception instead of a live thread.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code THREAD} selector family with the {@code reject}
 * effect applied to the {@code THREAD_START} operation. It intercepts {@code Thread.start()} and
 * aborts the call by throwing before the JVM's native thread-creation path runs. The annotation is
 * declared on the test class or method alongside a container annotation and is active for the
 * lifetime of the annotated scope (class-scope: {@code beforeAll} to {@code afterAll};
 * method-scope: {@code beforeEach} to {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code Thread.start()}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the native {@code start0()} call.
 *   <li>The reject effect constructs and throws the configured exception (default: {@link
 *       java.lang.OutOfMemoryError} with the configured message) from within the interceptor body.
 *   <li>The exception propagates up the stack to the caller of {@code thread.start()} — the thread
 *       object remains in the NEW state, its {@code run()} method is never invoked, and no OS
 *       thread is created.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The caller of {@code thread.start()} receives the configured exception — assert that the
 *       application code catches or propagates it correctly.
 *   <li>{@code thread.getState()} returns {@code Thread.State.NEW} after the failed start — the
 *       thread object is reusable (though most code does not attempt to restart a thread).
 *   <li>Thread pools that create workers on demand ({@code ThreadPoolExecutor}) propagate the error
 *       to the pool's {@code ThreadFactory} handler; assert that the pool's {@code
 *       rejectedExecutionHandler} is invoked rather than silently losing the task.
 *   <li>Frameworks that start background threads during application bootstrap (e.g. Spring's
 *       scheduler executor) throw before the bean is fully initialised; assert that the application
 *       context fails to start with a descriptive error rather than hanging.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a JVM that has exhausted its native
 * thread limit (typically controlled by {@code /proc/sys/kernel/threads-max} or the container's
 * {@code pids} cgroup limit), causing every new {@code Thread.start()} to throw {@link
 * java.lang.OutOfMemoryError}: unable to create native thread — thread pools fail to expand,
 * scheduled tasks queue up indefinitely, and the application degrades to partial availability
 * before the operator notices the OOM entries in the application log.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent retransforms {@code java.lang.Thread} via the
 * bootstrap class loader's instrumentation channel. The interceptor is installed on the public
 * {@code start()} method; the private native {@code start0()} is not touched. Because the exception
 * is thrown from Java bytecode (not from native), the JVM's thread bookkeeping tables are never
 * updated — {@code Thread.activeCount()} does not increase, and thread-dump tools show no trace of
 * the attempted thread.
 *
 * <p><strong>Error type and the ThreadFactory contract.</strong> {@link java.lang.OutOfMemoryError}
 * is the correct production error for thread-limit exhaustion. Many thread-pool implementations
 * catch {@link java.lang.Error} in their worker-spawn path and invoke the {@code
 * RejectedExecutionHandler}; well-written handlers log and enqueue the task for retry. Injecting
 * this error in tests validates that path without requiring a system-wide thread-limit change.
 *
 * <p><strong>Distinction from {@code ChaosThreadStartDelay}.</strong> The delay effect lets the
 * thread start eventually (after a park). The reject effect is terminal for that particular {@code
 * start()} call. Use reject to exercise error-handling and fallback logic; use delay to exercise
 * latency tolerance.
 *
 * <p><strong>Interaction with virtual threads.</strong> {@code Thread.ofVirtual().start(runnable)}
 * routes through the same {@code Thread.start()} entrypoint, so this annotation also fires for
 * virtual thread starts on JDK 21+. Use {@code @ChaosVirtualThreadStartReject} to isolate the fault
 * to virtual threads only.
 *
 * <p><strong>Cascading effects on application startup.</strong> Many frameworks (Spring Boot,
 * Quarkus) launch background threads during the context-refresh phase. Rejecting those starts
 * aborts the application before it accepts any traffic, exercising the health-check and
 * orchestrator restart logic that production deployments depend on.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadStartReject(message = "thread limit exhausted")
 * class ThreadStartRejectedTest {
 *
 *   @Test
 *   void poolRejectsTaskWhenThreadCannotStart(AppConnectionInfo info) {
 *     assertThatThrownBy(() -> client.submitHeavyTask(info))
 *         .isInstanceOf(RejectedExecutionException.class);
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
 * @see com.macstab.chaos.jvm.api.OperationType#THREAD_START
 * @see com.macstab.chaos.jvm.api.ChaosSelector#thread(java.util.Set)
 * @see ChaosThreadStartDelay
 * @see ChaosVirtualThreadStartReject
 */
@Repeatable(ChaosThreadStartReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.THREAD,
    operationType = OperationType.THREAD_START)
public @interface ChaosThreadStartReject {

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
   * @ChaosThreadStartReject(id = "primary",  probability = 0.001)
   * @ChaosThreadStartReject(id = "replica",  probability = 0.01)
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
    ChaosThreadStartReject[] value();
  }
}
