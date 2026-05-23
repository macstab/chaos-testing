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
 * Drives a continuous storm of JVM safepoints inside the target container by issuing
 * {@code System.gc()} calls on a tight interval, repeatedly stopping all application threads for
 * stop-the-world pauses.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor issues repeated {@code System.gc()} calls separated by {@link #gcIntervalMs()}
 * milliseconds. Each call forces the JVM to bring all application threads to a safepoint,
 * producing a stop-the-world pause even if the heap does not need collection.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>A background stressor thread loops, sleeping {@link #gcIntervalMs()} milliseconds between
 *       iterations, and calls {@code System.gc()} on each iteration.</li>
 *   <li>Each {@code System.gc()} call causes the JVM to request a safepoint: the VM thread sets
 *       a safepoint-pending flag, and every application thread checks that flag at the next
 *       safepoint poll — typically at method-exit bytecodes, backward branches, or JNI entry/exit
 *       boundaries. Threads that reach the poll suspend themselves.</li>
 *   <li>Once all threads are at a safepoint, the garbage collector runs a full collection (or a
 *       no-op if the GC respects {@code -XX:+DisableExplicitGC}), then the VM thread clears the
 *       flag and all application threads resume.</li>
 *   <li>The cycle repeats continuously. With a 100 ms interval the application experiences at
 *       least ten stop-the-world pauses per second in addition to any pauses triggered by normal
 *       GC activity.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Elevated application latency.</strong> Every in-flight request is paused at the
 *       next safepoint poll; P99 and max latency increase by at least the safepoint duration.
 *       Assert that SLA-bound endpoints remain within timeout even when pauses recur at
 *       {@link #gcIntervalMs()} frequency.
 *   <li><strong>Timeout and deadline failures.</strong> Deadlines measured with wall-clock time
 *       ({@code System.currentTimeMillis()}) advance during the pause; code that checks a deadline
 *       immediately after the pause may find it already expired. Assert that retry logic and
 *       deadline propagation handle intermittent stop-the-world pauses.
 *   <li><strong>Liveness-probe failures.</strong> Kubernetes liveness probes that call an HTTP
 *       endpoint may time out during a stop-the-world pause; assert that the probe's
 *       {@code timeoutSeconds} is sized to survive the pause duration or that the probe uses a
 *       dedicated thread that is less likely to be at a safepoint boundary.
 *   <li><strong>Log flooding.</strong> GC logs ({@code -Xlog:gc*}) will show a very high safepoint
 *       frequency and large "time to safepoint" values; assert that GC log appenders do not
 *       introduce significant I/O latency themselves.
 *   <li><strong>Production failure mode:</strong> a JVM with many long-running native calls (JNI,
 *       file I/O blocked in native) exhibits high "time to safepoint" because threads in native
 *       code cannot poll the safepoint flag until they return to Java. This stressor reproduces the
 *       elevated safepoint frequency without requiring native-call pressure.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>JVM safepoints are global pause points that the VM uses for operations requiring a consistent
 * heap view: garbage collection, biased-lock revocation, thread stack sampling, class redefinition,
 * and deoptimisation. The JVM inserts safepoint polls — typically a read from a memory-mapped page
 * that is made inaccessible when a safepoint is pending — at backward branches and method exits in
 * compiled code. When the page access faults (or the flag is set in interpreted mode), the thread
 * suspends itself at that poll location.
 *
 * <p>The pause has two measurable components: "time to safepoint" (TTSP) — the interval from when
 * the VM thread requests the safepoint to when the last application thread has stopped — and "time
 * at safepoint" — the duration of the stop-the-world operation itself. TTSP is bounded by how
 * frequently application threads reach a safepoint poll; long-running loops without backward
 * branches, or code that spends most of its time in JNI calls, can cause TTSP to spike to tens or
 * hundreds of milliseconds. The GC log with {@code -Xlog:safepoint} prints both values.
 *
 * <p>When {@code -XX:+DisableExplicitGC} is set, the JVM converts explicit {@code System.gc()}
 * calls to no-ops, which means this stressor will not trigger GC cycles — but it will still
 * request safepoints for the GC operation, depending on the JVM implementation. On most HotSpot
 * builds, {@code DisableExplicitGC} prevents the GC work but does not prevent the safepoint
 * handshake. On ZGC and Shenandoah (concurrent collectors), the "stop-the-world" phase is very
 * short (sub-millisecond), so this stressor has a less severe effect on those collectors than on
 * G1 or Parallel GC where full collections pause for much longer.
 *
 * <p>The stressor interacts with {@link ChaosGcPressure}: combining both causes both allocation
 * pressure (which triggers GC collection cycles) and explicit GC calls (which trigger safepoints
 * independently of allocation). The combination exercises whether the application can sustain
 * throughput when GC is both frequently triggered and heap-pressure-driven.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSafepointStorm(gcIntervalMs = 50)
 * class SafepointLatencyTest {
 *   @Test
 *   void p99LatencyRemainsWithinSlaUnderSafepointStorm(ConnectionInfo info) { ... }
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
@Repeatable(ChaosSafepointStorm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SafepointStormTranslator")
public @interface ChaosSafepointStorm {

  /**
   * @return interval between forced GCs in ms
   */
  long gcIntervalMs() default 100L;

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
   * @ChaosSafepointStorm(id = "primary",  probability = 0.001)
   * @ChaosSafepointStorm(id = "replica",  probability = 0.01)
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
    ChaosSafepointStorm[] value();
  }
}
