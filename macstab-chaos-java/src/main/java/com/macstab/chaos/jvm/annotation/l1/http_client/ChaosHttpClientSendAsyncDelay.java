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
 * Intercepts {@code HttpClient.sendAsync()} and delays the completion of the returned {@code
 * CompletableFuture} by {@link #delayMs()} milliseconds, inflating async HTTP latency without
 * blocking the calling thread.
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
 *       the call on the calling thread.
 *   <li>The interceptor sleeps for a duration drawn from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}] on the calling thread before {@code sendAsync} is invoked, so the delay is
 *       observable as time-to-{@code CompletableFuture} rather than time-to-completion.
 *   <li>The HTTP I/O then proceeds normally on the JDK's internal async I/O threads; the {@code
 *       CompletableFuture} completes when the response arrives.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The {@code CompletableFuture} returned by {@code sendAsync()} is obtained later than
 *       expected; reactive pipelines chained to it are delayed by the injected amount.
 *   <li>Timeout stages in reactive pipelines ({@code orTimeout()}, {@code completeOnTimeout()}) may
 *       fire; assert that the application handles {@code TimeoutException} from these stages.
 *   <li>Caller threads are blocked during the pre-send delay, so executor queues grow if many async
 *       sends are dispatched simultaneously — assert that bounded executors do not starve.
 *   <li><strong>Production failure mode:</strong> a reactive microservice fans out 20 async HTTP
 *       calls per request; with 3 s of injected delay each, all 20 futures complete late,
 *       triggering a cascade of {@code orTimeout()} completions and filling dead-letter queues.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code HttpClient.sendAsync()} in {@code jdk.internal.net.http.HttpClientImpl} submits work to
 * the client's internal executor and returns a {@code MinimalFuture} immediately. The chaos agent
 * intercepts the public {@code sendAsync} method before the submission, sleeping on the calling
 * thread so that the delay appears as latency in obtaining the future rather than latency in the
 * future's completion. This distinction matters for reactive frameworks that start timeout clocks
 * at future-acquisition time.
 *
 * <p>Because the sleep occurs before the executor submission, the JDK's HTTP/2 multiplexer and
 * async read pipeline are unaffected during the sleep — only the moment of submission is delayed.
 * This is different from intercepting at the response-body level; use {@link com.macstab.chaos.jvm.annotation.l1.network.ChaosSocketReadDelay}
 * or {@link com.macstab.chaos.jvm.annotation.l1.nio.ChaosNioChannelReadDelay} if you need to slow body consumption specifically.
 *
 * <p>The difference between {@link ChaosHttpClientSendDelay} and this annotation is the threading
 * model: {@code Send} blocks the caller synchronously, while {@code SendAsync} blocks only until
 * the future is returned, after which the caller thread is free. When measuring impact on
 * reactive-pipeline throughput, {@code SendAsync} delay is the correct primitive because it matches
 * how production code uses {@code sendAsync()}.
 *
 * <p>Combining this with {@link ChaosHttpClientSendAsyncReject} in separate test methods allows
 * verification of both slow-response and no-response paths through the same application code.
 *
 * <p>When {@link #maxDelayMs()} exceeds {@link #delayMs()}, the jitter tests race conditions in
 * reactive merge operators ({@code Flux.merge}, {@code CompletableFuture.allOf}) where the first
 * future to complete triggers downstream logic before slow futures resolve.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosHttpClientSendAsyncDelay(delayMs = 1000, maxDelayMs = 3000)
 * class AsyncLatencyTest {
 *   @Test
 *   void reactivePipelineRespectsTimeout(ConnectionInfo info) {
 *     // assert that orTimeout(2s) fires when jitter exceeds 2 s
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
 * @see ChaosHttpClientSendDelay
 * @see ChaosHttpClientSendAsyncReject
 * @see ChaosHttpClientSendAsyncInjectException
 */
@Repeatable(ChaosHttpClientSendAsyncDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.HTTP_CLIENT,
    operationType = OperationType.HTTP_CLIENT_SEND_ASYNC)
public @interface ChaosHttpClientSendAsyncDelay {

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
   * @ChaosHttpClientSendAsyncDelay(id = "primary",  probability = 0.001)
   * @ChaosHttpClientSendAsyncDelay(id = "replica",  probability = 0.01)
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
    ChaosHttpClientSendAsyncDelay[] value();
  }
}
