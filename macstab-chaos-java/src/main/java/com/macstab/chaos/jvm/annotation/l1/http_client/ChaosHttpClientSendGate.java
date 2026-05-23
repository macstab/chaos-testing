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
 * Intercepts {@code HttpClient.send()} and blocks the calling thread on an internal gate for up to
 * {@link #maxBlockMs()} milliseconds, holding all in-flight HTTP requests in a pending state until
 * the gate is released or the timeout elapses.
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
 *       HttpResponse.BodyHandler)}, the chaos agent intercepts the thread and parks it on a shared
 *       {@code CountDownLatch} or {@code CyclicBarrier} internal to the gate mechanism.
 *   <li>All threads blocked at the gate accumulate: if ten threads call {@code send()}
 *       simultaneously, all ten park.
 *   <li>After {@link #maxBlockMs()} milliseconds the gate opens automatically and all parked
 *       threads are released to proceed with their HTTP calls simultaneously, creating a burst of
 *       concurrent outbound requests.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>All outbound HTTP requests are held for the gate duration; the application appears to hang
 *       from the perspective of any load balancer or upstream caller performing health-checks.
 *   <li>After gate release, a thundering-herd burst hits the downstream service simultaneously;
 *       assert that the downstream handles the burst or that the application has back-pressure.
 *   <li>Connection-pool exhaustion: all threads hold pool slots while blocked; assert that the pool
 *       does not grow unboundedly or that requests beyond pool capacity are rejected with a clear
 *       error.
 *   <li><strong>Production failure mode:</strong> a network partition lasting 25 seconds causes
 *       hundreds of HTTP threads to block in {@code send()}; when connectivity is restored, all
 *       requests fire at once — the downstream service receives a spike 100x normal traffic and
 *       begins dropping requests, starting a secondary incident.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The gate translator installs a {@code CountDownLatch(1)} shared across all intercepted calls.
 * Each intercepted thread calls {@code latch.await(maxBlockMs, TimeUnit.MILLISECONDS)}. The latch
 * is released either by the chaos agent's scheduler thread after the timeout, or by a test-side API
 * call that signals early release. Both paths are safe under concurrent access.
 *
 * <p>Because all threads block before any socket work, the JDK's internal HTTP connection pool
 * ({@code jdk.internal.net.http.ConnectionPool}) does not see the requests until after gate
 * release. This means the pool is not exhausted during the gate period — exhaustion happens only
 * after release when all threads compete for a bounded pool simultaneously. If the goal is to test
 * pool exhaustion in isolation, pair this with a pool size configured below the number of concurrent
 * test threads.
 *
 * <p>The gate is distinct from a simple delay ({@link ChaosHttpClientSendDelay}) in that the gate
 * synchronises threads: all threads wait together and are released together. This models a
 * connection reset followed by reconnect storms, whereas a delay models uniform per-request
 * latency. Combine a short gate ({@code maxBlockMs=5000}) with a pool of 10 and 50 concurrent test
 * threads to reproduce connection pool saturation after a short outage.
 *
 * <p>The interceptor is installed on {@code jdk.internal.net.http.HttpClientImpl#send} via Byte
 * Buddy. JDK internal classes require {@code --add-opens} on Java 17+ if the agent does not use
 * {@code Instrumentation.redefineClasses} with the bootstrap loader; the chaos agent handles this
 * automatically at startup via the {@code Can-Retransform-Classes: true} manifest attribute.
 *
 * <p>After the gate opens, blocked threads are unparked in an unspecified order determined by the
 * JVM thread scheduler. On Linux with many-core systems, all threads typically resume within a
 * single scheduler quantum, producing a tight burst. On resource-constrained environments the burst
 * may spread over several milliseconds.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosHttpClientSendGate(maxBlockMs = 10_000)
 * class ThunderingHerdTest {
 *   @Test
 *   void applicationSurvivesBurstAfterGateOpens(ConnectionInfo info) {
 *     // send 50 concurrent requests, all held for 10 s then released simultaneously
 *     // assert downstream did not receive more than pool-size concurrent connections
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
 * @see ChaosHttpClientSendDelay
 * @see ChaosHttpClientSendReject
 */
@Repeatable(ChaosHttpClientSendGate.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.GateTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.HTTP_CLIENT,
    operationType = OperationType.HTTP_CLIENT_SEND)
public @interface ChaosHttpClientSendGate {

  /**
   * @return maximum block duration in milliseconds
   */
  long maxBlockMs() default 30_000L;

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
   * @ChaosHttpClientSendGate(id = "primary",  probability = 0.001)
   * @ChaosHttpClientSendGate(id = "replica",  probability = 0.01)
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
    ChaosHttpClientSendGate[] value();
  }
}
