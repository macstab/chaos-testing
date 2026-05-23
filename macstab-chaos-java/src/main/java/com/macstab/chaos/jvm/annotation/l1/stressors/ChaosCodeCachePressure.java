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
 * Forces the JVM's JIT compiler to fill the code cache by generating and hot-compiling a large
 * number of synthetic methods, driving the JVM into code-cache saturation mode where new
 * compilations are queued indefinitely and previously compiled methods may be evicted.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept
 * a specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor generates {@link #classCount()} synthetic classes with {@link #methodsPerClass()}
 * methods each, loads them, calls them in a tight loop to satisfy the JIT's invocation-count
 * threshold, and retains them to prevent eviction from metadata structures until the rule is
 * removed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent uses a bytecode generation library to generate {@link #classCount()} synthetic
 *       classes, each with {@link #methodsPerClass()} distinct methods that contain enough
 *       bytecode to be non-trivial (at least a few arithmetic operations to prevent inlining).</li>
 *   <li>Each method is called in a loop until the JIT's invocation counter reaches the
 *       compilation threshold ({@code -XX:CompileThreshold}, default 10,000 for C2). The JIT
 *       compiler then enqueues the method for native code compilation.</li>
 *   <li>Each compiled method occupies space in the code cache (a native memory region bounded by
 *       {@code -XX:ReservedCodeCacheSize}, default 240 MB). As the cache fills, the JVM emits a
 *       warning log message and stops accepting new compilations.</li>
 *   <li>When the code cache is full and no eviction happens, newly hot methods remain in
 *       interpreted mode. The application's own hot methods may also lose compilation slots,
 *       causing the interpreter to run them instead of compiled native code.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Throughput regression.</strong> When the JIT stops compiling new methods, hot
 *       application code that was not yet compiled (e.g. code first triggered by a production-
 *       volume load test) runs in the interpreter, which is typically 5–10x slower than
 *       JIT-compiled code; assert that throughput does not drop below an acceptable threshold.
 *   <li><strong>Compilation queue backlog.</strong> The JIT compiler's compilation queue
 *       ({@code -XX:+PrintCompilation} or JFR {@code jdk.Compilation} events) will show a growing
 *       backlog with no completions; assert that the monitoring pipeline detects this and alerts.
 *   <li><strong>Code cache full warning in logs.</strong> The JVM logs "CodeCache is full" at
 *       warning level when the cache saturates; assert that the log monitoring pipeline captures
 *       this message and does not discard it as a debug line.
 *   <li><strong>Method deoptimisation after eviction.</strong> When the cache sweeper eventually
 *       reclaims space by evicting old nmethods, threads executing those methods are deoptimised
 *       and fall back to the interpreter mid-execution; assert that the application handles
 *       mid-flight deoptimisation without throwing unexpected exceptions.
 *   <li><strong>Production failure mode:</strong> a dynamically deployed application (e.g. a
 *       plugin system, a hot-reload framework, or an application server with many deployed WARs)
 *       that generates many unique classes at startup can fill the code cache before the
 *       application's own hot methods are compiled, causing permanently degraded throughput.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The JVM's code cache is a single contiguous native memory region reserved at startup by
 * {@code -XX:ReservedCodeCacheSize}. It stores nmethods (compiled native code for Java methods),
 * JVM stubs, and adapter frames. In Java 9+, the code cache is divided into three segments:
 * non-method (JVM stubs), profiled (C1 tier compilations), and non-profiled (C2 tier). Each
 * segment has its own size limit.
 *
 * <p>When the non-profiled segment fills, the JVM switches to "CodeCache is full" mode: the JIT
 * compiler stops enqueuing new compilations. Existing compiled methods continue to run, but hot
 * methods that have not been compiled yet remain in the interpreter. The JVM's code cache sweeper
 * thread periodically evicts old nmethods to reclaim space; this eviction can cause threads to
 * be deoptimised if they are executing an evicted method.
 *
 * <p>The stressor fills the cache with synthetic methods: {@link #classCount()} classes ×
 * {@link #methodsPerClass()} methods. The total code cache consumption depends on the average
 * native code size per method (typically 200–500 bytes of machine code after C2 compilation).
 * With 5,000 classes and 50 methods each (250,000 methods total) and an average of 300 bytes
 * per compiled method, total cache usage is approximately 75 MB — enough to saturate a 240 MB
 * cache when combined with the application's own compiled methods.
 *
 * <p>The stressor interacts with {@link ChaosMetaspacePressure}: generating classes for code
 * cache pressure also consumes Metaspace (for class metadata and constant pools). Running both
 * stressors simultaneously exercises both native memory subsystems at once.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer(jvmArgs = "-XX:ReservedCodeCacheSize=64m")
 * @JvmAgentChaos
 * @ChaosCodeCachePressure(classCount = 2000, methodsPerClass = 30)
 * class CodeCacheExhaustionTest {
 *   @Test
 *   void throughputDoesNotCollapseBeyondAcceptableThresholdOnCodeCacheFull(ConnectionInfo info) { ... }
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
@Repeatable(ChaosCodeCachePressure.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.CodeCachePressureTranslator")
public @interface ChaosCodeCachePressure {

  /**
   * @return number of synthetic classes to JIT-compile (> 0)
   */
  int classCount() default 5000;

  /**
   * @return methods per generated class (> 0)
   */
  int methodsPerClass() default 50;

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
   * @ChaosCodeCachePressure(id = "primary",  probability = 0.001)
   * @ChaosCodeCachePressure(id = "replica",  probability = 0.01)
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
    ChaosCodeCachePressure[] value();
  }
}
