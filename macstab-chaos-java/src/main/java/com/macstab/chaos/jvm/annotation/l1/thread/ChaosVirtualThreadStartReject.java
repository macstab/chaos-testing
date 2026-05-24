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
 * Throws the configured exception inside the virtual-thread start path before the {@code
 * Continuation} is submitted to the carrier fork-join pool — the virtual thread never mounts, its
 * {@code Runnable} is never executed, and the caller receives an exception.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code THREAD} selector family with the {@code reject}
 * effect applied to the {@code VIRTUAL_THREAD_START} operation (Project Loom, JDK 21+). It aborts
 * the virtual thread creation before the carrier-pool submission step, exercising error-handling
 * paths in Loom-based concurrency code that are never triggered under normal operation. The
 * annotation is declared on the test class or method alongside a container annotation and is active
 * for the lifetime of the annotated scope.
 *
 * <p>This annotation fires only for virtual threads. Platform (OS-backed) threads are not affected.
 * Use {@code @ChaosThreadStartReject} to target platform threads, or both together to reject all
 * thread creation.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on the internal virtual-thread start path.
 * When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the virtual thread's {@code Continuation} is handed to the
 *       carrier fork-join pool.
 *   <li>The reject effect constructs and throws the configured exception (default message: {@code
 *       "rejected by chaos L1"}) from within the interceptor body.
 *   <li>The exception propagates to the caller of {@code Thread.ofVirtual().start(runnable)} or
 *       {@code StructuredTaskScope.fork(callable)} — the virtual thread is never created at the JVM
 *       level and no carrier slot is consumed.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The caller of the virtual-thread start receives the configured exception; assert that the
 *       application propagates or wraps it correctly (e.g. as a {@code RejectedExecutionException}
 *       from the framework layer).
 *   <li>{@code Thread.activeCount()} does not increase — the rejected virtual thread is not
 *       registered in the JVM's thread table.
 *   <li>{@code StructuredTaskScope.fork()} propagates the exception to the scope's exception
 *       handler; assert that the scope cancels remaining subtasks and re-throws on {@code join()}.
 *   <li>HTTP servers that serve each request on a new virtual thread return a 503 or connection
 *       reset to the client; assert the client receives an appropriate HTTP error code rather than
 *       a timeout.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a JVM-level resource guard (e.g. a
 * custom {@code ThreadFactory} that enforces a cap on concurrent virtual threads) throws when the
 * cap is exceeded — a high-traffic Loom-based HTTP server rejects new requests by failing to fork
 * virtual threads, causing HTTP 503 responses until the in-flight count drops below the cap.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> Virtual thread creation in JDK 21+ calls the
 * package-private {@code VirtualThread#start()} method, which differs from the platform-thread
 * {@code Thread#start()} at the bytecode level. The agent targets this internal override via the
 * bootstrap instrumentation channel. Rejecting here means the {@code Continuation} object is
 * allocated but never enqueued in the fork-join work queue, so the carrier pool sees no evidence of
 * the attempted thread.
 *
 * <p><strong>Structured concurrency interaction.</strong> {@code StructuredTaskScope} wraps each
 * {@code fork()} call in a try-catch for {@code RejectedExecutionException}; the scope's completion
 * handler records the failure and triggers scope cancellation. This annotation's exception will
 * surface through that path — verify that the scope's {@code Throwable} collector holds the
 * injected exception so that diagnostics are preserved.
 *
 * <p><strong>Carrier-pool isolation.</strong> Because the rejection fires before the {@code
 * Continuation} enters the fork-join queue, the carrier pool's worker threads are completely
 * unaffected. This makes the reject effect invisible to JVM flight-recorder carrier utilisation
 * metrics — the only observable signal is the exception in the calling thread's stack.
 *
 * <p><strong>Distinction from {@code ChaosVirtualThreadStartDelay}.</strong> The delay effect lets
 * the virtual thread start eventually (after a park on the calling thread). The reject effect is
 * terminal: the thread is never created. Use reject to test fallback logic such as circuit breakers
 * or graceful degradation under thread-creation failure.
 *
 * <p><strong>Exception type note.</strong> The default exception message is passed as a {@code
 * RuntimeException} wrapper by the {@code RejectTranslator}. Configure {@code exceptionClassName}
 * if the application code catches a specific type such as {@code
 * java.util.concurrent.RejectedExecutionException}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosVirtualThreadStartReject(message = "virtual thread cap exceeded")
 * class VirtualThreadRejectTest {
 *
 *   @Test
 *   void serverResponds503WhenVirtualThreadCreationFails(AppConnectionInfo info) {
 *     int status = client.getStatus(info, "/api/resource");
 *     assertThat(status).isEqualTo(503);
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
 *   <li>A Java 21+ container image — virtual threads require JDK 21 or later.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#VIRTUAL_THREAD_START
 * @see com.macstab.chaos.jvm.api.ChaosSelector#thread(java.util.Set)
 * @see ChaosVirtualThreadStartDelay
 * @see ChaosThreadStartReject
 */
@Repeatable(ChaosVirtualThreadStartReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.THREAD,
    operationType = OperationType.VIRTUAL_THREAD_START)
public @interface ChaosVirtualThreadStartReject {

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
   * @ChaosVirtualThreadStartReject(id = "primary",  probability = 0.001)
   * @ChaosVirtualThreadStartReject(id = "replica",  probability = 0.01)
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
    ChaosVirtualThreadStartReject[] value();
  }
}
