/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.class_loading;

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
 * Intercepts {@code ClassLoader.loadClass()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the class is located and resolved, simulating slow class loading from a
 * network-attached JAR repository, a high-latency class data sharing (CDS) miss, or a heavily
 * loaded custom class loader in application frameworks that use dynamic loading.
 *
 * <h2>What this annotation is</h2>
 *
 * A JVM agent L1 chaos primitive — one typed annotation per (selector family, operation type,
 * effect) tuple. It is declared on a test class or method alongside a container annotation and
 * activates for the lifetime of the test class ({@code beforeAll} / {@code afterAll}) or a single
 * test method ({@code beforeEach} / {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>Before every call to {@code java.lang.ClassLoader#loadClass(String)} inside the target
 *       container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code loadClass()} executes normally, delegating to
 *       the parent class loader (delegation model) or searching the class loader's own resources.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Spring's application context refresh resolves hundreds of bean classes via
 *       {@code Class.forName()} which calls {@code ClassLoader.loadClass()}; the delay inflates
 *       the context startup time; assert that the application's startup probe has sufficient
 *       initialPeriodSeconds to tolerate slow class loading.
 *   <li>{@code ClassLoader.loadClass()} holds a lock on the {@code ClassLoader} object during
 *       loading (the JVM's parallel class loading does not fully eliminate lock contention);
 *       with a delay, multiple threads trying to load different classes through the same class
 *       loader serialise; assert that the application does not deadlock under concurrent class
 *       loading with the delay applied.
 *   <li>Frameworks that use lazy class loading for bean definitions (Spring's lazy init, CDI's
 *       normal scope proxies) will incur the delay on first use of each lazily loaded bean;
 *       assert that the first-request latency spike is bounded and does not exceed SLO limits.
 *   <li><strong>Production failure mode:</strong> a class loader leak causes the JVM's metaspace
 *       to fill; the GC spends increasing time sweeping metaspace for unreachable classes; each
 *       {@code loadClass()} call takes longer because the class loader hierarchy is deeper due
 *       to accumulated leaked class loaders; new class loads begin failing with
 *       {@code OutOfMemoryError: Metaspace}; this annotation models the slow-loadClass symptom
 *       without requiring an actual metaspace leak.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.lang.ClassLoader#loadClass(String, boolean)}, the
 * protected method that implements the class loading protocol. The public
 * {@code ClassLoader.loadClass(String)} delegates to this method with {@code resolve = false}.
 * JVM class loading follows the delegation model: a class loader first delegates to its parent;
 * only if the parent cannot load the class does it attempt loading itself. The chaos delay fires
 * before the parent delegation call, adding to the total time for the entire delegation chain.
 *
 * <p>Parallel class loading (enabled via {@code ClassLoader.registerAsParallelCapable()}) allows
 * multiple threads to load different classes from the same class loader concurrently by using
 * per-class-name locks rather than a per-class-loader lock. The chaos delay fires before the lock
 * is acquired; threads waiting for a delayed load of the same class name are serialised by the
 * per-name lock. If many classes are loaded concurrently and all are delayed, the total class
 * loading time grows linearly with the number of distinct class names being loaded.
 *
 * <p>Spring's {@code ClassPathBeanDefinitionScanner} uses {@code ClassLoader.loadClass()} to
 * load candidate bean classes for inspection during component scanning. On a large classpath with
 * many {@code @Component}-annotated classes, this is called hundreds of times during startup.
 * The chaos delay applies to each call, multiplying the startup time by the number of scanned
 * classes divided by any parallelism in the scanning process.
 *
 * <p>Groovy, JRuby, and other dynamic JVM languages generate classes at runtime using
 * {@code ClassLoader.defineClass()} (see {@link ChaosClassDefineDelay}); their script loading
 * also calls {@code loadClass()} to resolve referenced types. The delay applies to both the
 * generated classes and the referenced standard library classes.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosClassLoadDelay(delayMs = 50)
 * class SlowClassLoadingStartupTest {
 *   @Test
 *   void startupProbeToleratesSlowClassLoading(ConnectionInfo info) {
 *     // assert application becomes READY within the configured probe timeout
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the
 *       translator class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClassLoadInjectException
 * @see ChaosClassDefineDelay
 * @see ChaosResourceLoadDelay
 */
@Repeatable(ChaosClassLoadDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.CLASS_LOADING,
    operationType = OperationType.CLASS_LOAD)
public @interface ChaosClassLoadDelay {

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
   * @ChaosClassLoadDelay(id = "primary",  probability = 0.001)
   * @ChaosClassLoadDelay(id = "replica",  probability = 0.01)
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
    ChaosClassLoadDelay[] value();
  }
}
