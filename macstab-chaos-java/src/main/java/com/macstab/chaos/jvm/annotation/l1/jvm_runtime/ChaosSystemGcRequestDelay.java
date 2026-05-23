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
 * Delays every explicit {@code System.gc()} call by a configurable number of milliseconds,
 * simulating a JVM under such GC pressure that a requested collection cannot be honoured promptly.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code SYSTEM_GC_REQUEST} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code System.gc()} in the target container's
 *       JVM.
 *   <li>Before forwarding the call, the interceptor parks the calling thread for a duration sampled
 *       uniformly between {@link #delayMs()} and {@link #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real {@code System.gc()} executes normally; the caller resumes after
 *       both the injected delay and the actual GC pause complete.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Off-heap cleanup stalls.</strong> NIO code that calls {@code System.gc()} to
 *       prompt collection of {@code DirectByteBuffer} objects will find that off-heap memory is not
 *       reclaimed promptly; assert that the application does not allocate unboundedly while waiting.
 *   <li><strong>Finalizer-dependent shutdown blocks.</strong> Shutdown hooks that rely on
 *       {@code System.gc()} to run finalizers before closing resources will hang for the injected
 *       delay; assert that the container still shuts down within the pod termination grace period.
 *   <li><strong>RMI / distributed GC heartbeat delayed.</strong> Java RMI uses periodic
 *       {@code System.gc()} calls to ensure distributed garbage collection; assert that remote
 *       references are not prematurely invalidated.
 *   <li><strong>Production failure mode:</strong> memory-sensitive libraries (e.g. Netty's
 *       pooled allocator) call {@code System.gc()} when off-heap pressure is high; delaying
 *       the response causes the allocator to throw {@code OutOfMemoryError: Direct buffer memory}
 *       while the GC is still blocked in the injected sleep.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code System.gc()} is a hint to the JVM that the application believes it would be a good
 * time to run a full collection. In HotSpot the method is native and typically triggers a full
 * stop-the-world collection unless {@code -XX:+DisableExplicitGC} is set. Intercepting it requires
 * the same native-method delegation pattern used for {@code System.currentTimeMillis()}: the agent
 * installs a Java-visible wrapper in the bootstrap class loader, injects the delay in that wrapper,
 * then delegates to the real native implementation.
 *
 * <p>The delay lengthens the time from when the caller invokes {@code System.gc()} to when the
 * collection actually begins, but the GC pause duration itself is unaffected. A test that asserts
 * "direct buffers are freed within N milliseconds of System.gc()" needs to account for both the
 * injected delay and the GC pause, making the assertion timeout window explicit.
 *
 * <p>Combining this annotation with {@link ChaosSystemGcRequestSuppress} (in a repeatable
 * annotation block) on different containers lets a test verify that the cluster tolerates one node
 * whose GC is delayed while another node's GC is completely suppressed — a common pattern when
 * testing mixed-health cluster behaviour.
 *
 * <p>The delay is applied on the thread that issued the {@code System.gc()} call. If the
 * application calls {@code System.gc()} from a finalizer or shutdown-hook thread, the chaos will
 * manifest as a slow shutdown rather than a slow runtime operation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSystemGcRequestDelay(delayMs = 500, maxDelayMs = 2_000)
 * class GcDelayTest {
 *   @Test
 *   void directBufferReleasedEvenWithSlowGc(ConnectionInfo info) { ... }
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
@Repeatable(ChaosSystemGcRequestDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.SYSTEM_GC_REQUEST)
public @interface ChaosSystemGcRequestDelay {

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
   * @ChaosSystemGcRequestDelay(id = "primary",  probability = 0.001)
   * @ChaosSystemGcRequestDelay(id = "replica",  probability = 0.01)
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
    ChaosSystemGcRequestDelay[] value();
  }
}
