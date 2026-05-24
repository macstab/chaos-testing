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
 * Retains a configurable amount of Java heap memory in fixed-size chunks for the duration of the
 * test, shrinking the effective heap available to the application and driving up GC frequency
 * without generating temporary garbage.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept a
 * specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor allocates {@link #bytes()} of heap memory in {@link #chunkSizeBytes()}-sized byte
 * arrays and holds strong references to all of them for the duration of the rule, preventing GC
 * from reclaiming the retained memory.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent computes the number of chunks needed: {@code bytes / chunkSizeBytes}. It
 *       allocates each chunk as a {@code byte[chunkSizeBytes]} and stores all chunks in a
 *       stressor-owned list.
 *   <li>The retained chunks are live objects. The GC must treat them as part of the live set and
 *       cannot reclaim them. This reduces the effective free heap by {@link #bytes()} bytes.
 *   <li>If the sum of the retained heap and the application's own live set exceeds the configured
 *       heap size ({@code -Xmx}), the GC frequency increases and eventually the JVM throws {@code
 *       OutOfMemoryError: Java heap space}.
 *   <li>At cleanup (rule removal), the stressor drops all references, making all chunks eligible
 *       for GC. The next minor or major GC will reclaim the retained memory and restore the
 *       effective heap to its original size.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Reduced available heap for application objects.</strong> Caches, session data, and
 *       in-flight request objects must compete for the remaining heap; assert that the application
 *       performs reasonable eviction (e.g. LRU cache shrinks) rather than retaining unbounded data
 *       until OOM.
 *   <li><strong>Increased major-GC frequency.</strong> With a smaller effective free heap, old-gen
 *       fills faster; major GC pauses (which stop the world for longer than minor GCs) occur more
 *       often; assert that the application's latency SLA is met even during old-gen collection.
 *   <li><strong>OOM on over-retention.</strong> Setting {@code bytes} close to or exceeding {@code
 *       -Xmx} minus the application's own live set will cause an OOM; assert that the OOM handler
 *       (UncaughtExceptionHandler, a JVM crash dump, or a Kubernetes OOM kill) is configured and
 *       produces a useful diagnostic artefact.
 *   <li><strong>GC pause-time SLA breach.</strong> A heap that is mostly full causes the GC to do
 *       more work per cycle (fewer free regions to compact into in G1, longer mark phases); assert
 *       that pause-time targets ({@code -XX:MaxGCPauseMillis}) are met or that the application
 *       responds to missed targets with a controlled degradation.
 *   <li><strong>Production failure mode:</strong> a memory leak (growing cache, session data not
 *       cleaned up, event-listener leak) gradually consumes the heap in exactly this pattern —
 *       retained objects crowd out transient ones, GC runs harder and longer, and eventually the
 *       JVM OOMs. This stressor reproduces the memory-pressure symptom without requiring an actual
 *       leak to develop over hours.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The stressor retains memory in the old generation (tenured space) because the chunks are
 * allocated, immediately promoted by the stressor's background thread (if it outlives one or two
 * minor GC cycles), and held strongly. Unlike {@link ChaosGcPressure}, which generates short-lived
 * garbage to stress the young-generation collector, this stressor creates long-lived retained
 * objects that stress the old-generation collector and the heap's free-space invariants.
 *
 * <p>The {@link #chunkSizeBytes()} parameter controls the chunk granularity. Larger chunks (e.g. 32
 * MB) allocate faster and are retained in fewer GC regions, which is representative of large object
 * allocations (LOBs) that skip the young generation in G1 (Humongous Objects). Smaller chunks (e.g.
 * 1 MB) are retained in many GC regions, which is more representative of normal application object
 * retention and gives the GC finer-grained control over which regions to select for collection.
 *
 * <p>Combining this stressor with {@link ChaosGcPressure} produces a compound scenario: high
 * allocation rate (from {@code ChaosGcPressure}) combined with reduced effective free heap (from
 * this stressor) creates a situation where the GC cannot keep up with allocations — the classic
 * precondition for an allocation-rate-induced OOM. The combination is useful for testing whether
 * the application's OOM handling and the container restart policy work correctly under the worst
 * realistic heap condition.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer(jvmArgs = "-Xmx256m")
 * @JvmAgentChaos
 * @ChaosHeapPressure(bytes = 128_000_000L, chunkSizeBytes = 1_048_576)
 * class HeapPressureTest {
 *   @Test
 *   void applicationHandlesLowHeapAvailability(ConnectionInfo info) { ... }
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
@Repeatable(ChaosHeapPressure.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.HeapPressureTranslator")
public @interface ChaosHeapPressure {

  /**
   * @return total bytes to allocate and retain (> 0)
   */
  long bytes() default 67_108_864L;

  /**
   * @return per-chunk size in bytes (> 0)
   */
  int chunkSizeBytes() default 1_048_576;

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
   * @ChaosHeapPressure(id = "primary",  probability = 0.001)
   * @ChaosHeapPressure(id = "replica",  probability = 0.01)
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
    ChaosHeapPressure[] value();
  }
}
