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
 * Delays every {@code ByteBuffer.allocateDirect()} call by a configurable number of milliseconds,
 * simulating off-heap allocator contention or a system under direct-memory pressure.
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
 *   <li>Before forwarding the call, the interceptor parks the calling thread for a duration sampled
 *       uniformly between {@link #delayMs()} and {@link #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real {@code allocateDirect()} executes and the buffer is returned
 *       normally to the caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>NIO network I/O throughput drops.</strong> Netty and other NIO frameworks acquire
 *       direct buffers from a pool on each read/write cycle; assert that the application's
 *       throughput remains above an acceptable minimum and that latency percentiles stay within SLA
 *       bounds even when buffer acquisition is slow.
 *   <li><strong>SSL handshake latency increases.</strong> TLS engines use direct buffers for
 *       plaintext and ciphertext; a delay per allocation amplifies handshake latency. Assert that
 *       connection establishment timeouts are configured with sufficient headroom.
 *   <li><strong>Pooled allocator falls back to heap.</strong> Netty's pooled allocator will use a
 *       heap buffer if the direct allocation fails or times out; assert that the fallback path does
 *       not introduce correctness errors.
 *   <li><strong>Production failure mode:</strong> in high-throughput NIO applications, slow direct
 *       buffer allocation can starve the I/O thread of buffers, causing it to block and leaving
 *       all channels unreadable until the allocation completes — effectively halting the service.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ByteBuffer.allocateDirect(int)} calls {@code new DirectByteBuffer(int)}, which invokes
 * the unsafe off-heap memory allocator via {@code Bits.reserveMemory()} and
 * {@code unsafe.allocateMemory()}. On the fast path this is a native {@code malloc} call and
 * returns quickly; on the slow path (when the JVM's direct-memory limit tracked by
 * {@code java.nio.Bits} is close to {@code -XX:MaxDirectMemorySize}) the JVM triggers a GC and
 * may retry several times before either succeeding or throwing
 * {@code OutOfMemoryError: Direct buffer memory}.
 *
 * <p>The agent intercepts at the {@code ByteBuffer.allocateDirect(int)} level using Byte Buddy
 * method-entry advice. The delay fires before the actual allocation, so it adds to the total
 * wall time of the allocation regardless of whether the slow path is taken. This simulates the
 * conditions seen when the OS page allocator is under pressure (e.g. on a host with high memory
 * fragmentation or NUMA imbalance) and {@code malloc} returns slowly even though the JVM's
 * logical direct-memory limit is not yet reached.
 *
 * <p>Combined with {@link ChaosDirectBufferAllocateSuppress}, tests can create a scenario where
 * some allocations are slow and others return {@code null}, matching the intermittent failure
 * pattern seen during hypervisor memory balloon inflation where the host occasionally refuses to
 * map additional physical pages.
 *
 * <p>This annotation is distinct from the {@link ChaosDirectBufferPressure} stressor: the delay
 * annotation targets specific allocation call sites and is probabilistic / scoped, whereas the
 * stressor proactively exhausts the entire direct-memory pool via background allocations.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosDirectBufferAllocateDelay(delayMs = 50, maxDelayMs = 200)
 * class DirectBufferDelayTest {
 *   @Test
 *   void nettyThroughputAboveMinimumUnderSlowBufferAlloc(ConnectionInfo info) { ... }
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
@Repeatable(ChaosDirectBufferAllocateDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.DIRECT_BUFFER_ALLOCATE)
public @interface ChaosDirectBufferAllocateDelay {

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
   * @ChaosDirectBufferAllocateDelay(id = "primary",  probability = 0.001)
   * @ChaosDirectBufferAllocateDelay(id = "replica",  probability = 0.01)
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
    ChaosDirectBufferAllocateDelay[] value();
  }
}
