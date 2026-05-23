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
 * Intercepts {@code ClassLoader.defineClass()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the bytecode is parsed and the class object is registered in the JVM,
 * simulating slow dynamic class generation as produced by Spring AOP proxies, Hibernate
 * enhancement, ByteBuddy instrumentation, and Groovy/JRuby script compilation.
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
 *   <li>Before every call to {@code java.lang.ClassLoader#defineClass(String, byte[], int, int)}
 *       or its variants inside the target container's JVM, the chaos agent intercepts the calling
 *       thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code defineClass()} executes normally, parsing the
 *       bytecode, verifying it, and creating a new {@code Class} object in the JVM's metaspace.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Spring AOP creates CGLIB proxy subclasses via {@code defineClass()} for every proxied
 *       bean at application context startup; the delay inflates Spring context startup time
 *       proportionally to the number of proxied beans; assert that the startup readiness probe
 *       is configured with sufficient timeout.
 *   <li>Hibernate's bytecode enhancer generates enhanced entity subclasses via {@code defineClass()}
 *       for each entity class found during scanning; the delay inflates JPA
 *       {@code EntityManagerFactory} initialisation time; assert that the JPA container does not
 *       time out during initialisation.
 *   <li>Groovy and JRuby compile script classes at runtime using {@code defineClass()} on each
 *       script or method; in a Groovy-heavy application, the first call to each script incurs the
 *       delay; assert that the application warms up before sending production traffic.
 *   <li><strong>Production failure mode:</strong> a metaspace leak causes the JVM to spend
 *       increasing time on metaspace GC; the {@code defineClass()} call itself takes longer as
 *       the JVM scans metaspace for space to allocate the new class descriptor; eventually
 *       {@code defineClass()} throws {@code OutOfMemoryError: Metaspace}; the chaos delay models
 *       the intermediate slow-defineClass symptom before the fatal OOM.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.lang.ClassLoader#defineClass(String, byte[], int, int)}
 * and its overloads. {@code defineClass()} is the lowest-level API for creating a class from
 * bytecode; all higher-level class loading paths (reading from JAR, generating bytecode
 * programmatically) ultimately call this method. The chaos delay fires before the JVM parses the
 * bytecode array, adding a predictable delay to the time the class definition is held in memory
 * as a raw byte array rather than as a resolved {@code Class} object.
 *
 * <p>Spring AOP's CGLIB-based proxying ({@code ObjenesisCglibAopProxy}) generates proxy
 * subclasses using ASM bytecode manipulation and then calls {@code defineClass()} to register
 * them. This happens once per proxied bean type during application context refresh. The proxy
 * class is cached after the first definition; subsequent beans of the same type reuse the cached
 * proxy class without calling {@code defineClass()} again.
 *
 * <p>Hibernate's {@code ByteBuddyProxyFactory} generates entity proxies using Byte Buddy, which
 * internally calls {@code defineClass()} for each generated proxy type. The delay fires during
 * the first access to each proxied entity type. In applications with many entity classes (50+),
 * the cumulative delay across all proxy definitions can significantly inflate startup time.
 *
 * <p>The distinguish from {@link ChaosClassLoadDelay}: load-delay adds time to finding and
 * reading an existing class from the classpath; define-delay adds time to registering a
 * dynamically generated class. Frameworks that generate classes at runtime (AOP proxies, code
 * generators) are primarily affected by define-delay; frameworks that load pre-compiled classes
 * are primarily affected by load-delay.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosClassDefineDelay(delayMs = 200)
 * class SpringAopProxyGenerationTest {
 *   @Test
 *   void contextStartupToleratesSlowCglibProxyDefinition(ConnectionInfo info) {
 *     // assert Spring context starts within readiness probe timeout
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
 * @see ChaosClassDefineSuppress
 * @see ChaosClassLoadDelay
 */
@Repeatable(ChaosClassDefineDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.CLASS_LOADING,
    operationType = OperationType.CLASS_DEFINE)
public @interface ChaosClassDefineDelay {

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
   * @ChaosClassDefineDelay(id = "primary",  probability = 0.001)
   * @ChaosClassDefineDelay(id = "replica",  probability = 0.01)
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
    ChaosClassDefineDelay[] value();
  }
}
