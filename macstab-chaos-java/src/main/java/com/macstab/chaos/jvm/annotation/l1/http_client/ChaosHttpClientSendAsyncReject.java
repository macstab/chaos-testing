/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.http_client;

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
 * Intercepts {@code HttpClient.sendAsync()} and immediately throws {@code java.io.IOException}
 * before returning the {@code CompletableFuture}, forcing the async HTTP call to fail synchronously
 * at the call site rather than via an exceptionally-completed future.
 *
 * <h2>What this annotation is</h2>
 *
 * A JVM agent L1 chaos primitive — one typed annotation per (selector family, operation type,
 * effect) tuple. It is declared on a test class or method alongside a container annotation and
 * activates for the lifetime of the test class ({@code beforeAll} / {@code afterAll}) or a single
 * test method ({@code beforeEach} / {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>Before every call to {@code java.net.http.HttpClient#sendAsync(HttpRequest,
 *       HttpResponse.BodyHandler)} inside the target container's JVM, the chaos agent intercepts
 *       the calling thread.
 *   <li>The agent throws {@code java.io.IOException} immediately — no {@code CompletableFuture} is
 *       returned, no network activity occurs, and no thread-pool task is submitted.
 *   <li>The exception propagates synchronously to the caller; any chained {@code .thenApply} or
 *       {@code .exceptionally} stages are never reached because the future was never created.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Code that does not wrap {@code sendAsync()} in a try-catch (relying on the future for error
 *       delivery) will receive an unexpected synchronous exception; assert that this boundary is
 *       handled correctly.
 *   <li>Reactive pipelines that call {@code sendAsync()} inside a {@code Mono.fromFuture()} block
 *       must propagate the synchronous throw as an error signal; assert that the pipeline does not
 *       swallow it silently.
 *   <li>Application error-tracking (Sentry, Datadog APM) should capture the thrown exception;
 *       assert that the error metric is incremented.
 *   <li><strong>Production failure mode:</strong> a reactive fan-out service calls {@code
 *       sendAsync()} for 10 downstream services; if the HTTP client is in a bad state (e.g.
 *       shutdown), all 10 calls throw synchronously, and uncaught exceptions in a reactive operator
 *       can terminate the reactive pipeline operator thread — causing an outage.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The JDK specification for {@code HttpClient.sendAsync()} does not declare a checked exception
 * in its signature, but the implementation in {@code jdk.internal.net.http.HttpClientImpl} can
 * throw {@code IllegalArgumentException} for invalid requests and wrap networking errors in
 * unchecked exceptions during future completion. This annotation injects the fault at the
 * synchronous entry point — before the method body executes — which is a distinct failure mode from
 * a future that completes exceptionally after I/O.
 *
 * <p>Application code written defensively will wrap {@code sendAsync()} invocations: {@code
 * CompletableFuture.supplyAsync(() -> client.sendAsync(req, handler))} converts the synchronous
 * throw into an exceptionally-completed future. This annotation tests whether that defensive
 * wrapping is present and correct; if it is absent the exception propagates to the executor's
 * uncaught exception handler.
 *
 * <p>The difference between this annotation and {@link ChaosHttpClientSendAsyncInjectException} is
 * that {@code Reject} always uses {@code IOException}, matching the network domain, while {@code
 * InjectException} allows specifying any exception class including unchecked types that might be
 * thrown by validation logic before network access.
 *
 * <p>Unlike {@link ChaosHttpClientSendReject}, which affects synchronous {@code send()}, this
 * annotation specifically targets the async path. In applications that mix both call styles,
 * combining both annotations with different probabilities allows testing the error-handling paths
 * for each independently.
 *
 * <p>No JDK connection pool slots, file descriptors, or executor-thread resources are consumed
 * because the throw happens before any resource acquisition. This makes the fault safe to inject at
 * high frequency in load tests without accumulating leaked resources.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosHttpClientSendAsyncReject(message = "async send rejected")
 * class AsyncSendRejectedTest {
 *   @Test
 *   void reactivePipelineSurfacesErrorCorrectly(ConnectionInfo info) {
 *     // assert that the Mono/Flux emits an error signal and does not hang
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the translator
 *       class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosHttpClientSendReject
 * @see ChaosHttpClientSendAsyncInjectException
 * @see ChaosHttpClientSendAsyncDelay
 */
@Repeatable(ChaosHttpClientSendAsyncReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.HTTP_CLIENT,
    operationType = OperationType.HTTP_CLIENT_SEND_ASYNC)
public @interface ChaosHttpClientSendAsyncReject {

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
   * @ChaosHttpClientSendAsyncReject(id = "primary",  probability = 0.001)
   * @ChaosHttpClientSendAsyncReject(id = "replica",  probability = 0.01)
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
    ChaosHttpClientSendAsyncReject[] value();
  }
}
