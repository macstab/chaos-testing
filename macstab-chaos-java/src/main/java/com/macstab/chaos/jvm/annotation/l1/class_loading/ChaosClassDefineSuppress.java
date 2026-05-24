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
 * Intercepts {@code ClassLoader.defineClass()} and returns {@code null} without registering the
 * class in the JVM, causing Spring AOP proxy generation, Hibernate bytecode enhancement, and any
 * framework that generates classes at runtime to receive a null class reference and fail with
 * {@code NullPointerException} or {@code IllegalStateException} when attempting to use the class.
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
 *   <li>Before every call to {@code java.lang.ClassLoader#defineClass(String, byte[], int, int)} or
 *       its variants inside the target container's JVM, the chaos agent intercepts the calling
 *       thread.
 *   <li>The agent returns {@code null} without calling the real {@code defineClass()}; no class
 *       object is created; no bytecode is parsed or verified; no entry is added to the class
 *       loader's internal class table.
 *   <li>The caller receives {@code null} from {@code defineClass()}; most callers assume a non-null
 *       return value and immediately dereference the result, causing {@code NullPointerException}
 *       at the point of use.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Spring AOP's CGLIB proxy factory calls {@code defineClass()} and immediately uses the
 *       returned class to create a proxy instance; a null return causes {@code
 *       NullPointerException} inside CGLIB's {@code Enhancer}; Spring wraps this as {@code
 *       BeanCreationException}; assert that the application fails fast and clearly rather than
 *       hanging in an inconsistent proxy state.
 *   <li>Hibernate's {@code ByteBuddyProxyFactory} uses the returned class to instantiate entity
 *       proxies; a null return causes {@code NullPointerException} inside the proxy factory; the
 *       {@code EntityManagerFactory} creation fails; assert that the JPA container propagates this
 *       as a deployment-time error rather than a runtime error on first entity access.
 *   <li>Groovy's {@code GroovyClassLoader} stores the returned class in its class cache; a null
 *       return means the class is not cached; subsequent attempts to use the script fail with
 *       {@code ClassNotFoundException} because the class was never registered.
 *   <li><strong>Production failure mode:</strong> a custom class loader in an OSGi container or
 *       plugin framework returns {@code null} from {@code defineClass()} due to a race condition in
 *       its class table; the framework receives a null class reference; the subsequent {@code
 *       newInstance()} call throws {@code NullPointerException}; the plugin fails to load; the OSGi
 *       container marks it as FAILED; dependent bundles fail to start; the failure cascades through
 *       the bundle dependency graph.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.lang.ClassLoader#defineClass()} and returns {@code null}.
 * The JVM contract for {@code defineClass()} is that it returns a non-null {@code Class} object or
 * throws an exception; returning {@code null} is a violation of this contract that the JVM does not
 * enforce — callers are expected to never receive null. This annotation exploits the unchecked
 * assumption to inject a null into the class generation pipeline.
 *
 * <p>Because {@code defineClass()} is the terminal operation of class generation, a suppressed
 * define is not retried by the JVM — the caller must handle the null or not. Unlike a failed {@code
 * loadClass()} which the JVM caches as a class-loading failure, a suppressed {@code defineClass()}
 * does not create any failure record; the caller may attempt to define the same class again on a
 * subsequent call, and the suppress effect will fire again if the fault window is still active.
 *
 * <p>This annotation is significantly more disruptive than {@link ChaosClassDefineDelay}: the delay
 * allows class definition to eventually succeed; the suppress prevents it entirely. Most frameworks
 * that call {@code defineClass()} do not have retry logic for a null return; the failure is
 * immediate and fatal for the affected class. Use this annotation to test class definition failure
 * handling in plugin frameworks and dynamic class generation libraries.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosClassDefineSuppress
 * class AopProxyDefinitionFailureTest {
 *   @Test
 *   void springContextFailsFastOnProxyDefinitionFailure(ConnectionInfo info) {
 *     // assert BeanCreationException is thrown and application does not hang
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the translator
 *       class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClassDefineDelay
 * @see ChaosClassLoadInjectException
 */
@Repeatable(ChaosClassDefineSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.CLASS_LOADING,
    operationType = OperationType.CLASS_DEFINE)
public @interface ChaosClassDefineSuppress {

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
   * @ChaosClassDefineSuppress(id = "primary",  probability = 0.001)
   * @ChaosClassDefineSuppress(id = "replica",  probability = 0.01)
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
    ChaosClassDefineSuppress[] value();
  }
}
