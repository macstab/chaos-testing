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
 * Sustains a configurable allocation rate inside the target container's JVM to drive continuous
 * GC activity, simulating the garbage production of a high-throughput service under peak load.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor allocates byte arrays at a rate of {@link #allocationRateBytesPerSecond()} bytes
 * per second for up to {@link #durationMs()} milliseconds, discarding each array immediately to
 * ensure it is eligible for the next minor GC.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>A background stressor thread loops, computing the number of bytes to allocate in each
 *       iteration to sustain the target rate. It allocates byte arrays of a small fixed size
 *       (typically 1–4 KB) and immediately drops the reference, making the arrays short-lived
 *       garbage.</li>
 *   <li>The sustained allocation rate fills the young generation (Eden space) rapidly, triggering
 *       frequent minor GCs (stop-the-world pauses of 1–20 ms depending on live-set size). If the
 *       allocation rate exceeds the minor-GC throughput, objects are promoted to the old generation,
 *       eventually triggering a major GC or full GC.</li>
 *   <li>The stressor runs for at most {@link #durationMs()} milliseconds from activation, then
 *       exits. If the test finishes before the duration elapses, the rule is removed and the
 *       stressor stops.</li>
 *   <li>The combined allocation load from the stressor plus the application's own allocation
 *       rate determines total GC pressure. A rate of 100 MB/s from the stressor combined with
 *       50 MB/s from the application produces 150 MB/s total, which is typical of a busy service
 *       with JSON serialisation.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Increased minor-GC frequency.</strong> The GC log ({@code -Xlog:gc}) will show
 *       minor GCs firing every few hundred milliseconds; assert that the application's latency
 *       P99 does not exceed SLA even under this GC frequency.
 *   <li><strong>Promotion to old generation.</strong> If the stressor rate is high enough that
 *       arrays survive past a minor GC (unlikely with immediately discarded short-lived arrays,
 *       but possible under concurrent stressor and application load), the old generation fills;
 *       assert that a major GC does not cause an OOM before the stressor finishes.
 *   <li><strong>Allocation failure under heap pressure.</strong> With a small heap
 *       ({@code -Xmx}) and a high stressor rate, the application's own allocations may fail with
 *       {@code OutOfMemoryError: Java heap space}; assert that the OOM handler shuts down the
 *       application gracefully rather than leaving it in a half-functional state.
 *   <li><strong>GC logging verbosity.</strong> Under high GC activity, GC log files can grow
 *       faster than expected; assert that log rotation is configured correctly and that disk
 *       pressure from GC logs does not starve I/O on data paths.
 *   <li><strong>Production failure mode:</strong> a service that generates excessive garbage (e.g.
 *       deserialising large JSON payloads without streaming, or creating many temporary objects in
 *       a hot path) saturates the GC and causes latency spikes under peak load. This stressor
 *       reproduces that garbage-production rate without needing peak traffic.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Young-generation (minor) GCs in HotSpot are stop-the-world operations: all application
 * threads pause while the GC copies live objects from Eden to a survivor space. The pause duration
 * is proportional to the live set (the objects that survive the collection), not the total
 * allocated bytes. Because the stressor's byte arrays are immediately discarded, they add
 * allocation throughput without increasing the live set, which keeps minor-GC pauses short even
 * at high allocation rates. This makes the stressor a good model for ephemeral-object workloads.
 *
 * <p>The allocation rate is measured in bytes per second, but the JVM's TLAB (Thread-Local
 * Allocation Buffer) absorbs short bursts. The stressor thread may allocate in bursts between
 * sleep intervals rather than at a perfectly constant rate; the average rate over a second will
 * match {@link #allocationRateBytesPerSecond()}, but instantaneous rates may be higher. Tests
 * that are sensitive to burst allocation should set a lower rate or combine the stressor with
 * {@link ChaosHeapPressure} to also retain some of the allocated objects.
 *
 * <p>Different GC algorithms respond differently: G1 GC will trigger concurrent marking when the
 * heap occupancy exceeds the initiating heap occupancy percent ({@code -XX:InitiatingHeapOccupancyPercent});
 * ZGC and Shenandoah run concurrently with the application and have lower pause budgets; Parallel
 * GC uses throughput-maximising heuristics that may sacrifice individual pause latency. The stressor
 * is collector-agnostic but its observable effect depends on which collector is configured.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosGcPressure(allocationRateBytesPerSecond = 200_000_000L, durationMs = 30_000L)
 * class GcPressureLatencyTest {
 *   @Test
 *   void p99LatencyRemainsWithinSlaUnderHighGcPressure(ConnectionInfo info) { ... }
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
@Repeatable(ChaosGcPressure.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.GcPressureTranslator")
public @interface ChaosGcPressure {

  /**
   * @return allocation rate in bytes/second (> 0)
   */
  long allocationRateBytesPerSecond() default 104_857_600L;

  /**
   * @return how long the stressor runs, in ms
   */
  long durationMs() default 60_000L;

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
   * @ChaosGcPressure(id = "primary",  probability = 0.001)
   * @ChaosGcPressure(id = "replica",  probability = 0.01)
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
    ChaosGcPressure[] value();
  }
}
