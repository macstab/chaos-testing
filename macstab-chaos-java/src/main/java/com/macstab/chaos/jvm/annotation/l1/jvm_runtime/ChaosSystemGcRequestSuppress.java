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
 * Silently suppresses every explicit {@code System.gc()} call, making the JVM behave as if
 * {@code -XX:+DisableExplicitGC} were active, so that application-driven GC pressure cannot be
 * relieved by explicit collections.
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
 *   <li>The interceptor returns immediately without forwarding to the JVM, making the call a
 *       no-op; no collection is triggered.
 *   <li>The calling thread is not delayed; only the GC hint is discarded.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Off-heap memory not reclaimed.</strong> {@code DirectByteBuffer} objects that
 *       would be collected on an explicit GC will accumulate, eventually causing
 *       {@code OutOfMemoryError: Direct buffer memory}; assert that the application either caps
 *       its off-heap usage or falls back gracefully.
 *   <li><strong>Finalizers never run on demand.</strong> Code that calls {@code System.gc()} to
 *       trigger finalizers (e.g. to close native resources) will see those finalizers deferred
 *       indefinitely; assert that resource cleanup uses explicit {@code close()} calls rather than
 *       finaliser reliance.
 *   <li><strong>RMI distributed GC broken.</strong> Java RMI's distributed GC mechanism depends
 *       on periodic {@code System.gc()} hints; with suppression, remote references may not be
 *       collected and the server-side proxy map grows without bound.
 *   <li><strong>Production failure mode:</strong> Netty's pooled allocator and other NIO
 *       frameworks call {@code System.gc()} as a last resort before throwing OOM; suppression
 *       prevents that safety valve and causes the JVM to OOM without ever running the
 *       collection that would have freed enough memory to continue.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code System.gc()} is implemented as a JVM intrinsic that, in HotSpot, ultimately calls
 * {@code Universe::heap()->collect()}. Suppressing it requires intercepting the Java-visible
 * {@code System.gc()} before the JNI boundary. The agent installs a Byte Buddy delegation stub
 * in the bootstrap class loader that checks whether the suppression rule is active and, if so,
 * returns without calling the native implementation.
 *
 * <p>Suppression differs from setting {@code -XX:+DisableExplicitGC} in that it is dynamic: the
 * agent can activate and deactivate the suppression at runtime via the agent API, enabling tests
 * to alternate between suppressed and permitted GC windows within a single test class lifecycle.
 * This is useful for testing "GC arrives too late" scenarios where the application allocates for N
 * seconds with suppression active, then GC is re-enabled to observe the catch-up behaviour.
 *
 * <p>Suppression does not affect JVM-initiated collections (minor GC, major GC, concurrent GC
 * cycles). Only the explicit {@code System.gc()} call from application code is intercepted. The
 * JVM remains free to collect at its own discretion, so the scenario is specifically "the
 * application asked for a GC and didn't get one", not "GC is entirely disabled".
 *
 * <p>The interaction with {@link ChaosSystemGcRequestDelay} on the same operation type is
 * undefined; applying both to the same container at the same time should be avoided. Use the
 * repeatable form to target different containers with different behaviours.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSystemGcRequestSuppress
 * class GcSuppressTest {
 *   @Test
 *   void directBufferOomHandledGracefullyWithoutExplicitGc(ConnectionInfo info) { ... }
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
 * @see com.macstab.chaos.jvm.api.OperationType#SYSTEM_GC_REQUEST
 * @see com.macstab.chaos.jvm.api.ChaosSelector#jvmRuntime(java.util.Set)
 */
@Repeatable(ChaosSystemGcRequestSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.SYSTEM_GC_REQUEST)
public @interface ChaosSystemGcRequestSuppress {

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
   * @ChaosSystemGcRequestSuppress(id = "primary",  probability = 0.001)
   * @ChaosSystemGcRequestSuppress(id = "replica",  probability = 0.01)
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
    ChaosSystemGcRequestSuppress[] value();
  }
}
