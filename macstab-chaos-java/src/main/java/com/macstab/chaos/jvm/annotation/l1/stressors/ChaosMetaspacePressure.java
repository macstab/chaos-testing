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
 * Exhausts the JVM's Metaspace by generating and loading large numbers of synthetic classes, each
 * with configurable static fields, simulating class-loading leaks in frameworks that generate proxy
 * classes or bytecode at runtime.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept a
 * specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor generates {@link #generatedClassCount()} synthetic classes, each with {@link
 * #fieldsPerClass()} static fields, and loads them via isolated class loaders so they cannot be
 * unloaded, consuming Metaspace for the duration of the rule.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The agent uses a bytecode generation library (Byte Buddy or ASM) to generate {@link
 *       #generatedClassCount()} distinct synthetic class definitions in memory. Each class has
 *       {@link #fieldsPerClass()} {@code static long} fields, increasing the per-class Metaspace
 *       footprint.
 *   <li>Each synthetic class is loaded via a fresh, isolated {@code ClassLoader} that has no parent
 *       capable of unloading the class. Because the class loader is kept reachable (held by the
 *       stressor), the JVM's class-unloading heuristic cannot reclaim the Metaspace occupied by
 *       these classes.
 *   <li>Metaspace usage grows monotonically from activation. If {@code -XX:MaxMetaspaceSize} is set
 *       and the stressor's classes exceed it, the JVM throws {@code OutOfMemoryError: Metaspace}
 *       before the stressor finishes loading all classes.
 *   <li>If no Metaspace limit is set (the default), Metaspace expands into native memory. The
 *       stressor then stresses the container's total memory limit (cgroup {@code
 *       memory.limit_in_bytes}) rather than a JVM-internal cap.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Metaspace OOM.</strong> When Metaspace is exhausted, the JVM throws {@code
 *       OutOfMemoryError: Metaspace} from the class-loading thread; assert that the application's
 *       exception handler (e.g. an uncaught exception handler on the thread pool) logs the OOM and
 *       does not silently swallow it.
 *   <li><strong>GC overhead from class-unloading sweeps.</strong> The JVM periodically sweeps for
 *       unloadable class loaders as part of full GC; with a large number of non-unloadable
 *       synthetic classes, the sweep time increases; assert that full-GC pauses do not push latency
 *       over SLA.
 *   <li><strong>Container OOM killed.</strong> Without {@code MaxMetaspaceSize}, Metaspace grows
 *       into native memory; when the container's total memory limit is reached, the OOM killer
 *       sends SIGKILL; assert that the readiness probe detects the restart and that the load
 *       balancer removes the instance before it starts rejecting requests.
 *   <li><strong>Production failure mode:</strong> applications using Spring, Hibernate, or other
 *       proxy-generating frameworks can leak class loaders when application contexts are repeatedly
 *       created (e.g. in a multi-tenant setup or a hot-reload scenario), causing Metaspace to grow
 *       without bound.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Metaspace (introduced in Java 8, replacing PermGen) stores the JVM's internal representation
 * of loaded classes: the bytecode, constant pool, method tables, vtables, and static field data.
 * Unlike the Java heap, Metaspace is allocated directly from native memory using {@code
 * mmap}/{@code VirtualAlloc}. It is bounded only by {@code -XX:MaxMetaspaceSize} (which defaults to
 * unlimited on most JVM distributions).
 *
 * <p>A class can be unloaded only if its class loader becomes unreachable (in the GC sense) — no
 * live references remain to the class loader or to any class it has loaded. The stressor defeats
 * this by holding strong references to all class loaders in a stressor-owned collection. As long as
 * the stressor is active, the JVM cannot unload the synthetic classes and cannot reclaim their
 * Metaspace.
 *
 * <p>Each synthetic class with {@link #fieldsPerClass()} static {@code long} fields contributes
 * roughly {@code 64 + fieldsPerClass * 8} bytes of static storage plus several kilobytes of class
 * metadata (method tables, constant pool, bytecode). The {@link #generatedClassCount()} parameter
 * therefore controls the total Metaspace consumption: 10,000 classes with 10 fields each consume
 * roughly 200–500 MB of Metaspace, depending on JVM version and GC policy.
 *
 * <p>The interaction with {@link ChaosGcPressure} is significant: GC pressure triggers full
 * collections which scan for unloadable class loaders; finding none (because the stressor holds
 * them), the JVM must request more Metaspace. If both are active simultaneously, the test exercises
 * the JVM's Metaspace expansion/GC co-ordination under combined pressure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer(jvmArgs = "-XX:MaxMetaspaceSize=128m")
 * @JvmAgentChaos
 * @ChaosMetaspacePressure(generatedClassCount = 8_000, fieldsPerClass = 5)
 * class MetaspaceExhaustionTest {
 *   @Test
 *   void applicationSurfacesMetaspaceOomRatherThanHanging(ConnectionInfo info) { ... }
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
@Repeatable(ChaosMetaspacePressure.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.MetaspacePressureTranslator")
public @interface ChaosMetaspacePressure {

  /**
   * @return number of synthetic classes to generate (> 0)
   */
  int generatedClassCount() default 10_000;

  /**
   * @return static fields per generated class
   */
  int fieldsPerClass() default 10;

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
   * @ChaosMetaspacePressure(id = "primary",  probability = 0.001)
   * @ChaosMetaspacePressure(id = "replica",  probability = 0.01)
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
    ChaosMetaspacePressure[] value();
  }
}
