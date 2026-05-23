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
 * Intercepts {@code HttpClient.send()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before allowing the request to proceed, inflating the end-to-end latency observed by
 * every synchronous HTTP call in the target JVM.
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
 *   <li>Before every call to {@code java.net.http.HttpClient#send(HttpRequest,
 *       HttpResponse.BodyHandler)} inside the target container's JVM, the chaos agent intercepts
 *       the thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]. When both values are equal the delay is deterministic.
 *   <li>Control returns to the caller only after the sleep completes; the underlying HTTP send
 *       then executes normally.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every {@code HttpClient.send()} call takes at least {@link #delayMs()} ms longer than
 *       without the fault; assert response-time histograms exceed the injected threshold.
 *   <li>Client-side timeouts ({@code HttpClient.connectTimeout()} or per-request timeouts) that
 *       are shorter than the injected delay will now fire — assert that the application throws
 *       {@code HttpTimeoutException} and handles it gracefully.
 *   <li>Thread-pool exhaustion: if many threads are blocked in send simultaneously, executor
 *       queue depths grow — assert that the application does not deadlock or reject work.
 *   <li><strong>Production failure mode:</strong> a downstream service slows from 20 ms to 4 s;
 *       every API thread is now blocked inside {@code send()}, the Tomcat/Undertow thread pool
 *       saturates, health-checks time out, and the load balancer removes the instance from
 *       rotation.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code HttpClient.send()} in the JDK 11+ implementation delegates through
 * {@code jdk.internal.net.http.HttpClientImpl#send}, which ultimately parks the calling thread
 * on a {@code CompletableFuture} that is completed by the HTTP/1.1 or HTTP/2 I/O event thread.
 * The chaos agent intercepts the public {@code send} method on {@code HttpClientImpl} via Byte
 * Buddy's {@code MethodDelegation} before the park occurs, so the artificial delay compounds with
 * any real network latency rather than overlapping it.
 *
 * <p>The interception point is at the boundary between application code and the JDK's internal
 * HTTP implementation, meaning it captures all callers — Spring's {@code RestTemplate} backed by
 * a JDK client, Quarkus REST client, or raw {@code HttpClient} usage — without modifying any
 * application class. The agent installs a single retransformation of {@code HttpClientImpl} at
 * startup and keeps the overhead to a single {@code Thread.sleep} on the matched threads.
 *
 * <p>When {@link #delayMs()} differs from {@link #maxDelayMs()}, the sleep duration is sampled
 * from a uniform distribution on each invocation, making this useful for jitter simulation. Jitter
 * is particularly effective at exposing retry-storm bugs: if a client retries immediately after a
 * timeout, and each retry hits another delay, requests pile up faster than they resolve.
 *
 * <p>The delay fires before the network call, so connection-pool accounting in frameworks like
 * Spring's {@code RestTemplate} or OkHttp is not disturbed — the pool slot is only claimed after
 * the sleep. This isolates pure latency effects from connection-pool exhaustion effects; use
 * {@link ChaosHttpClientSendGate} if you need the latter.
 *
 * <p>Unlike OS-level traffic shaping (e.g. {@code tc netem}), this injection is thread-precise:
 * only threads executing {@code HttpClient.send()} inside the target JVM are affected. Other
 * outbound paths (raw {@code Socket}, NIO channels) are not delayed unless separate rules are also
 * registered.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosHttpClientSendDelay(delayMs = 2000, maxDelayMs = 4000)
 * class SlowDownstreamTest {
 *   @Test
 *   void requestTimesOutAndReturns503(ConnectionInfo info) {
 *     // assert the application returns HTTP 503 within its own timeout budget
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the
 *       translator class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosHttpClientSendAsyncDelay
 * @see ChaosHttpClientSendReject
 * @see ChaosHttpClientSendGate
 */
@Repeatable(ChaosHttpClientSendDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.HTTP_CLIENT,
    operationType = OperationType.HTTP_CLIENT_SEND)
public @interface ChaosHttpClientSendDelay {

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
   * @ChaosHttpClientSendDelay(id = "primary",  probability = 0.001)
   * @ChaosHttpClientSendDelay(id = "replica",  probability = 0.01)
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
    ChaosHttpClientSendDelay[] value();
  }
}
