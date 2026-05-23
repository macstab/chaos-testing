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
 * Intercepts {@code ClassLoader.loadClass()} and throws the configured exception before the class
 * is resolved, simulating a missing dependency JAR, a corrupted class file, or a class loader
 * isolation violation that causes Spring's component scanning, JPA provider initialisation, or
 * plugin loaders to fail with {@code ClassNotFoundException} or {@code NoClassDefFoundError}.
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
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it; no class loading or resolution occurs.
 *   <li>The exception propagates to the caller — a {@code Class.forName()} call, a Spring bean
 *       factory, a JPA provider — which must handle the class-not-found condition gracefully or
 *       propagate it as a fatal initialisation error.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Spring's {@code AnnotationConfigApplicationContext.refresh()} calls {@code loadClass()}
 *       to load bean classes; an exception here causes {@code BeanDefinitionStoreException}
 *       wrapping the class loading error; the Spring context fails to start; assert that the
 *       application's failure mode is a clean shutdown rather than a hung process.
 *   <li>Hibernate's entity manager factory initialisation calls {@code loadClass()} to resolve
 *       entity classes declared in {@code persistence.xml} or found via scanning; a failure here
 *       causes {@code PersistenceException: Unable to build Hibernate SessionFactory}; assert
 *       that the JPA container properly propagates the error.
 *   <li>Inject {@code java.lang.ClassNotFoundException} (the natural exception from
 *       {@code loadClass()}) to model a genuinely missing class; inject
 *       {@code java.lang.LinkageError} to model a class that was found but could not be linked
 *       (e.g. incompatible bytecode version or broken class file).
 *   <li><strong>Production failure mode:</strong> a fat JAR is built with a dependency at
 *       version X, but a transitive dependency already includes version Y; the class loader
 *       finds version Y first; the application calls {@code loadClass()} for a class that exists
 *       in version X but not version Y; the resulting {@code ClassNotFoundException} propagates
 *       through many layers of framework initialization before manifesting as an unhelpful error
 *       message about a bean that failed to initialize.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.lang.ClassLoader#loadClass(String, boolean)}, which is
 * the protected implementation called by the public {@code loadClass(String)}. The thrown exception
 * bypasses the JVM's class loading protocol entirely; the parent class loader is never consulted.
 * The JVM's class loading lock for the target class name is not acquired because the intercept
 * fires before any lock acquisition.
 *
 * <p>When a {@code ClassNotFoundException} propagates from {@code ClassLoader.loadClass()}, callers
 * that used {@code Class.forName()} receive it directly. Callers that used reflection APIs
 * ({@code Method.invoke()}, {@code Field.get()}) receive a {@code NoClassDefFoundError} wrapping
 * the original exception if the class was partially resolved at compile time. These two exception
 * types trigger different handling in frameworks: Spring maps {@code NoClassDefFoundError} to a
 * {@code BeanCreationException}; Jakarta EE containers may treat it as a deployment error.
 *
 * <p>The JVM caches class loading failures: once a class fails to load, subsequent attempts to
 * load the same class from the same class loader throw {@code NoClassDefFoundError} with the
 * original failure as the cause, even if the underlying cause (the injected exception) is no
 * longer active. This annotation's effect may persist beyond the fault window for specific class
 * names if those names were attempted during the fault and cached as failed — restart the
 * container to clear the class loading failure cache.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosClassLoadInjectException(
 *     exceptionClassName = "java.lang.ClassNotFoundException",
 *     message = "com.example.SomeRequiredClass")
 * class MissingDependencyTest {
 *   @Test
 *   void applicationFailsCleanlyWithMissingClass(ConnectionInfo info) {
 *     // assert startup fails with BeanDefinitionStoreException and container exits
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
 * @see ChaosClassLoadDelay
 * @see ChaosClassDefineSuppress
 */
@Repeatable(ChaosClassLoadInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.CLASS_LOADING,
    operationType = OperationType.CLASS_LOAD)
public @interface ChaosClassLoadInjectException {

  /**
   * @return binary class name of the exception to throw (e.g. "java.io.IOException")
   */
  String exceptionClassName() default "java.io.IOException";

  /**
   * @return exception message
   */
  String message() default "injected by chaos L1";

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
   * @ChaosClassLoadInjectException(id = "primary",  probability = 0.001)
   * @ChaosClassLoadInjectException(id = "replica",  probability = 0.01)
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
    ChaosClassLoadInjectException[] value();
  }
}
