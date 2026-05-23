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
 * Suppresses every {@code ByteBuffer.allocateDirect()} call and returns {@code null} to the
 * caller, simulating complete off-heap memory exhaustion without actually consuming memory.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code DIRECT_BUFFER_ALLOCATE} operation — one
 * typed annotation per (selector family, operation type, effect) tuple. Declared on a test class
 * or {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code java.nio.ByteBuffer.allocateDirect(int)}
 *       in the target container's JVM.
 *   <li>The interceptor discards the call and returns {@code null} without allocating any off-heap
 *       memory.
 *   <li>The caller receives {@code null} where a valid {@code ByteBuffer} was expected, typically
 *       causing a {@code NullPointerException} at the first dereference site.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>NIO operations throw NullPointerException.</strong> Code that does not null-check
 *       the result of {@code allocateDirect()} will throw immediately; assert that the application
 *       catches the exception, logs a meaningful error, and falls back to a heap buffer if the
 *       operation permits it.
 *   <li><strong>SSL engine fails to initialise.</strong> The JDK TLS implementation uses direct
 *       buffers internally; a null allocation during handshake causes an
 *       {@code SSLException} or an NPE; assert that the connection attempt fails fast rather than
 *       hanging.
 *   <li><strong>Direct-buffer pool drains..</strong> Pooled NIO frameworks that pre-allocate a
 *       pool of direct buffers at startup will find the pool empty; assert that the framework
 *       emits a circuit-breaker event or degrades to a heap-buffer path.
 *   <li><strong>Production failure mode:</strong> when the JVM reaches
 *       {@code -XX:MaxDirectMemorySize}, {@code allocateDirect()} throws
 *       {@code OutOfMemoryError} rather than returning null; this annotation simulates the
 *       "allocation returns null" case that is specific to some custom allocator wrappers and
 *       Unsafe-based allocation that returns null on failure.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The standard {@code ByteBuffer.allocateDirect()} never returns {@code null} in the JDK — it
 * either succeeds or throws {@code OutOfMemoryError}. This annotation therefore tests code against
 * a contract that does not hold in practice for the standard allocator, but is realistic for
 * custom off-heap allocators (e.g. {@code sun.misc.Unsafe.allocateMemory()}-based allocators used
 * in some caches and databases) that can return a null or invalid address to indicate failure.
 *
 * <p>The agent uses Byte Buddy method-entry advice that short-circuits {@code allocateDirect()}
 * and returns {@code null} immediately. No native memory is allocated or reserved. This means the
 * JVM's direct-memory accounting is not affected, and code that checks
 * {@code ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage()} will not observe a change
 * in direct-memory consumption during the test.
 *
 * <p>A {@code null} return from {@code allocateDirect()} propagates to every call site that stores
 * the result without a null check. In practice, frameworks like Netty guard their allocations
 * carefully, so the most likely manifestation is an NPE in custom application code or in a
 * framework component that was not designed for allocation failure. The test is therefore probing
 * for defensive null checks in paths that are normally never reached.
 *
 * <p>Combine with {@link ChaosDirectBufferAllocateDelay} using the repeatable-annotation form to
 * create a mixed scenario where some allocations are slow and others return null — simulating an
 * allocator that is partially exhausted and intermittently failing.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosDirectBufferAllocateSuppress
 * class DirectBufferSuppressTest {
 *   @Test
 *   void sslHandshakeFallsBackToHeapBufferWhenDirectUnavailable(ConnectionInfo info) { ... }
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
 * @see com.macstab.chaos.jvm.api.OperationType#DIRECT_BUFFER_ALLOCATE
 * @see com.macstab.chaos.jvm.api.ChaosSelector#jvmRuntime(java.util.Set)
 */
@Repeatable(ChaosDirectBufferAllocateSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.DIRECT_BUFFER_ALLOCATE)
public @interface ChaosDirectBufferAllocateSuppress {

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
   * @ChaosDirectBufferAllocateSuppress(id = "primary",  probability = 0.001)
   * @ChaosDirectBufferAllocateSuppress(id = "replica",  probability = 0.01)
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
    ChaosDirectBufferAllocateSuppress[] value();
  }
}
