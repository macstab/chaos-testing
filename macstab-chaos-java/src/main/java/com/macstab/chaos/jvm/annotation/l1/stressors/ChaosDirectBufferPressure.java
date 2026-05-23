/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.stressors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Exhausts the JVM's off-heap direct buffer memory by allocating and retaining a configurable
 * total of {@code ByteBuffer.allocateDirect()} buffers, simulating NIO or Netty direct-memory
 * exhaustion under high-throughput I/O workloads.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor allocates {@link #totalBytes()} of direct (off-heap) memory in
 * {@link #bufferSizeBytes()}-sized chunks via {@code ByteBuffer.allocateDirect()} and retains all
 * buffers in a stressor-owned collection for the duration of the rule.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent computes the number of buffers needed: {@code totalBytes / bufferSizeBytes}.
 *       It allocates them in a loop, calling {@code ByteBuffer.allocateDirect(bufferSizeBytes)}
 *       on each iteration.</li>
 *   <li>Each allocation calls {@code sun.misc.Bits.reserveMemory()} (or its Java-9+ equivalent),
 *       which tracks the total direct memory usage against the JVM limit
 *       ({@code -XX:MaxDirectMemorySize}, defaulting to the same value as {@code -Xmx}).</li>
 *   <li>If the running total would exceed {@code MaxDirectMemorySize}, the JVM first attempts
 *       a {@code System.gc()} to release direct buffers whose heap-side {@code Cleaner} objects
 *       have been collected. If that still does not free enough, the allocation throws
 *       {@code OutOfMemoryError: Direct buffer memory}.</li>
 *   <li>The stressor holds strong references to all allocated buffers so their {@code Cleaner}
 *       objects are never GC-collected during the test; this prevents the JVM's emergency GC from
 *       reclaiming any stressor-owned direct memory.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>NIO channel allocation failures.</strong> Netty, Undertow, and other NIO-based
 *       servers allocate direct buffers for socket read/write operations; when direct memory is
 *       exhausted, channel reads fail with {@code OutOfMemoryError}; assert that the server closes
 *       the affected connections cleanly and does not crash the JVM.
 *   <li><strong>Netty allocator pool exhaustion.</strong> Netty's pooled buffer allocator
 *       pre-allocates large direct-memory arenas; exhaustion causes the allocator to fall back to
 *       heap buffers (slower) or to throw; assert that the server degrades gracefully to heap
 *       I/O rather than propagating the OOM to the request handler.
 *   <li><strong>JMX direct-memory metric spikes.</strong> The
 *       {@code java.nio:type=BufferPool,name=direct} MBean tracks total direct-memory capacity
 *       and usage; assert that your monitoring pipeline detects the spike and raises an alert
 *       before the limit is reached.
 *   <li><strong>Emergency GC trigger.</strong> When a direct buffer allocation exceeds the limit,
 *       the JVM calls {@code System.gc()} internally; assert that this does not interfere with
 *       the application's latency budget (GC competes with the application's own allocations).
 *   <li><strong>Production failure mode:</strong> a Netty-based service that leaks direct buffers
 *       (e.g. by failing to release a buffer after a partial write) exhausts direct memory
 *       gradually; the first symptom is intermittent {@code OutOfMemoryError: Direct buffer memory}
 *       on high-traffic paths, which is hard to diagnose without a direct-memory leak profiler.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Direct buffers are backed by native memory allocated via {@code malloc} (or
 * {@code VirtualAlloc} on Windows) rather than the Java heap. They are used by NIO channels to
 * avoid a data copy between the Java heap and native I/O buffers: a heap {@code ByteBuffer} must
 * be pinned or copied before it can be passed to the OS for I/O, whereas a direct buffer can be
 * passed to the OS directly.
 *
 * <p>The JVM tracks total direct-memory usage via a global atomic counter in
 * {@code java.nio.Bits} (internal) or its Java-9+ equivalent in {@code jdk.internal.misc}. When
 * a new allocation would push the counter above {@code MaxDirectMemorySize}, the JVM triggers a
 * {@code System.gc()} and waits briefly for the GC to complete. Cleaned buffers (whose
 * {@code Cleaner} has run) decrement the counter; if the counter drops below the limit, the
 * allocation succeeds. If not, an OOM is thrown.
 *
 * <p>The stressor defeats this recovery mechanism by holding all its buffers alive (no
 * {@code Cleaner} is eligible for GC). The application's own buffer allocations therefore hit the
 * limit head-on. The {@code bufferSizeBytes} parameter controls granularity: smaller buffers are
 * faster to allocate but create more {@code Cleaner} objects on the Java heap; larger buffers
 * are fewer but consume more native memory per allocation. A 1 MB default matches Netty's typical
 * arena chunk size.
 *
 * <p>This stressor differs from the {@code ChaosDirectBufferAllocateDelay} interceptor: the
 * interceptor slows individual allocation calls without consuming memory, while this stressor
 * consumes real native memory to drive the system into a genuine exhaustion state. The two can be
 * combined: the delay stressor slows the application's allocation path while this stressor ensures
 * that the limit is quickly reached.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer(jvmArgs = "-XX:MaxDirectMemorySize=256m")
 * @JvmAgentChaos
 * @ChaosDirectBufferPressure(totalBytes = 200_000_000L, bufferSizeBytes = 1_048_576)
 * class DirectMemoryExhaustionTest {
 *   @Test
 *   void serverDegradesToHeapIoWhenDirectMemoryExhausted(ConnectionInfo info) { ... }
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
@Repeatable(ChaosDirectBufferPressure.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.DirectBufferPressureTranslator")
public @interface ChaosDirectBufferPressure {

  /**
   * @return total bytes to allocate off-heap (> 0)
   */
  long totalBytes() default 268_435_456L;

  /**
   * @return per-buffer size in bytes (> 0)
   */
  int bufferSizeBytes() default 1_048_576;

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
   * @ChaosDirectBufferPressure(id = "primary",  probability = 0.001)
   * @ChaosDirectBufferPressure(id = "replica",  probability = 0.01)
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
    ChaosDirectBufferPressure[] value();
  }
}
