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
 * Delays every Java object serialisation call ({@code ObjectOutputStream.writeObject()}) by a
 * configurable number of milliseconds, simulating a slow serde pipeline or an overloaded
 * serialisation codec.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code OBJECT_SERIALIZE} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code ObjectOutputStream.writeObject(Object)} in
 *       the target container's JVM.
 *   <li>Before forwarding the call, the interceptor parks the calling thread for a duration sampled
 *       uniformly between {@link #delayMs()} and {@link #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real serialisation executes and the result is written to the
 *       underlying stream normally.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Session replication slow.</strong> Servlet containers that replicate HTTP sessions
 *       via Java serialisation will propagate session state to peers more slowly; assert that
 *       failover during replication does not expose stale or missing session data.
 *   <li><strong>RMI call latency increases.</strong> Java RMI serialises arguments before
 *       transmission; a delay per serialise call adds to the end-to-end RMI latency; assert that
 *       the remote call timeout is sufficient.
 *   <li><strong>Cached object storage slow.</strong> Caches that serialise objects to a remote
 *       store (e.g. Redis via Java serialisation) will be slower; assert that the application falls
 *       back to its local cache or degrades gracefully.
 *   <li><strong>Production failure mode:</strong> slow serialisation on a hot path (e.g. per-
 *       request session serialisation) can saturate the I/O thread pool, causing request queuing
 *       and eventually thread-pool exhaustion under load.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Java's built-in serialisation is invoked through {@code ObjectOutputStream.writeObject()},
 * which walks the object graph recursively, calling {@code writeObject()} on each reachable object.
 * The agent intercepts the top-level call; nested calls made during graph traversal are also
 * intercepted, so a large object graph with many serialisable sub-objects will accumulate multiple
 * delays. For deeply nested graphs this can produce unexpectedly large total delays; configure the
 * delay accordingly.
 *
 * <p>The intercept point is the public API method, not the native serialisation machinery inside
 * the JVM. This means the delay applies to all caller-initiated serialisations, including those
 * performed by third-party libraries that use Java serialisation internally (e.g. some JMS
 * providers, distributed cache clients, and legacy ORM frameworks).
 *
 * <p>Combining this annotation with {@link ChaosObjectDeserializeDelay} allows a test to simulate a
 * slow round-trip through a serialise/transmit/deserialise pipeline — for example, to validate that
 * a cluster-state synchronisation protocol handles slow members without stalling the quorum.
 *
 * <p>Note that Jackson, Gson, and Protobuf do not use {@code ObjectOutputStream} and are therefore
 * not affected by this annotation. Use the method-selector annotations (see {@code
 * ChaosMethodEnterInjectException}) to inject chaos into custom serialisation implementations that
 * bypass the standard serialisation API.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosObjectSerializeDelay(delayMs = 300, maxDelayMs = 1_000)
 * class SerializeDelayTest {
 *   @Test
 *   void sessionReplicationToleratesSlowSerde(ConnectionInfo info) { ... }
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
@Repeatable(ChaosObjectSerializeDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.OBJECT_SERIALIZE)
public @interface ChaosObjectSerializeDelay {

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
   * @ChaosObjectSerializeDelay(id = "primary",  probability = 0.001)
   * @ChaosObjectSerializeDelay(id = "replica",  probability = 0.01)
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
    ChaosObjectSerializeDelay[] value();
  }
}
