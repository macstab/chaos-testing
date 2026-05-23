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
 * Delays every Java object deserialisation call ({@code ObjectInputStream.readObject()}) by a
 * configurable number of milliseconds, simulating a slow inbound serde pipeline or a
 * deserialisation codec under CPU pressure.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code OBJECT_DESERIALIZE} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code ObjectInputStream.readObject()} in the
 *       target container's JVM.
 *   <li>Before forwarding the call, the interceptor parks the calling thread for a duration sampled
 *       uniformly between {@link #delayMs()} and {@link #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real deserialisation executes and the reconstructed object is returned
 *       normally to the caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Incoming RMI calls slow.</strong> Java RMI deserialises arguments on receipt;
 *       slow deserialisation blocks the RMI dispatcher thread, reducing the effective throughput
 *       of the server; assert that the client's call-timeout is reached gracefully.
 *   <li><strong>Session restoration slow at failover.</strong> Servlet containers that restore
 *       sessions from a replicated store on the first request after failover will be slow to serve
 *       the first response; assert that the response eventually arrives within SLA.
 *   <li><strong>Cache read latency increases.</strong> Distributed caches using Java serialisation
 *       will be slow on cache hit; assert that the application's timeout budget for cache reads
 *       accommodates deserialisation latency.
 *   <li><strong>Production failure mode:</strong> complex object graphs (many fields, deep
 *       nesting, cyclical references) require significant CPU and heap during deserialisation; a
 *       delay annotation amplifies this cost and can cause GC pressure or heap exhaustion on
 *       busy nodes that process many inbound serialised messages concurrently.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Java deserialisation via {@code ObjectInputStream.readObject()} is one of the most
 * CPU-intensive operations per byte of input in the standard JDK: it reads class descriptors,
 * resolves class names via the context class loader, allocates objects, and invokes
 * {@code readObject()} callbacks recursively for each sub-object. The agent intercepts at the
 * top-level {@code readObject()} call; nested calls during graph traversal are also intercepted,
 * accumulating delays proportional to the depth of the object graph.
 *
 * <p>The delay fires before the read operation, extending the time the calling thread holds any
 * lock it may have acquired around the read. If the application performs deserialisation under a
 * lock (a common pattern in synchronised cache implementations), the injected delay can cause lock
 * contention, which in turn amplifies the impact on other threads waiting for the same lock.
 *
 * <p>Deserialisation also poses a security risk: the JVM executes {@code readResolve()} and
 * {@code readObject()} methods on arbitrary objects read from the stream. The delay fires before
 * any of those callbacks run, so the total time from interception to method return includes both
 * the delay and any custom deserialisation logic. Tests should account for this.
 *
 * <p>For the exception-injection variant, see {@link ChaosObjectDeserializeInjectException}. For
 * the serialisation-side delay, see {@link ChaosObjectSerializeDelay}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosObjectDeserializeDelay(delayMs = 200, maxDelayMs = 800)
 * class DeserializeDelayTest {
 *   @Test
 *   void sessionRestorationAfterFailoverMeetsLatencySla(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an
 *       {@code ExtensionConfigurationException} at {@code beforeAll}.
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
@Repeatable(ChaosObjectDeserializeDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.OBJECT_DESERIALIZE)
public @interface ChaosObjectDeserializeDelay {

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
   * @ChaosObjectDeserializeDelay(id = "primary",  probability = 0.001)
   * @ChaosObjectDeserializeDelay(id = "replica",  probability = 0.01)
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
    ChaosObjectDeserializeDelay[] value();
  }
}
