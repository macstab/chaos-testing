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
 * Intercepts {@code ClassLoader.getResource()} and {@code ClassLoader.getResourceAsStream()} and
 * returns {@code null} without performing any classpath lookup, simulating missing classpath
 * resources such as absent {@code META-INF/services/} files, missing auto-configuration imports,
 * or a broken JAR index that causes Spring Boot, Hibernate, and service-loader-based frameworks
 * to silently skip registration of components they expect to find.
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
 *   <li>Before every call to {@code java.lang.ClassLoader#getResource(String)} and
 *       {@code ClassLoader#getResourceAsStream(String)} inside the target container's JVM, the
 *       chaos agent intercepts the calling thread.
 *   <li>The agent returns {@code null} immediately without performing any classpath scan; for
 *       {@code getResource()} this means no URL is returned; for {@code getResourceAsStream()}
 *       this means no InputStream is returned.
 *   <li>The caller receives {@code null} as if the resource does not exist on the classpath.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Java's {@code ServiceLoader} calls {@code ClassLoader.getResource("META-INF/services/
 *       &lt;interface-name&gt;")} to find service provider files; a null return means the service
 *       loader finds no providers; assert that the application detects the missing service
 *       provider and fails with a clear error rather than a {@code NullPointerException} deep
 *       in the code that expected a non-empty {@code ServiceLoader}.
 *   <li>Spring Boot's auto-configuration discovery reads
 *       {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports};
 *       a null return from {@code getResource()} causes all auto-configurations to be skipped;
 *       the application starts with no auto-configured beans; assert that missing auto-configuration
 *       is detected early and reported clearly rather than manifesting as mysterious missing
 *       functionality.
 *   <li>Hibernate's entity scanning reads mapping files via {@code getResourceAsStream()}; a null
 *       return causes Hibernate to find no mappings; assert that Hibernate fails at
 *       {@code EntityManagerFactory} creation with {@code org.hibernate.MappingException: No
 *       persistence units found} rather than silently starting with an empty schema.
 *   <li><strong>Production failure mode:</strong> a Maven Shade plugin misconfiguration produces
 *       a fat JAR where duplicate {@code META-INF/services/} files are overwritten rather than
 *       merged; the resulting JAR contains only the last contributor's services file; other
 *       service providers are silently absent; the application starts without errors but some
 *       features are non-functional because their service providers were never registered.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.lang.ClassLoader#getResource(String)} and
 * {@code ClassLoader#getResourceAsStream(String)}, returning {@code null} for all resource
 * lookups. The JVM's contract for {@code getResource()} allows null as a legitimate return when
 * the resource is not found; unlike the null-return from {@code defineClass()} (which violates
 * the contract), returning null here is semantically correct and exercises the same code path as
 * a genuinely absent classpath resource.
 *
 * <p>Java's {@code java.util.ServiceLoader} reads service provider files via
 * {@code ClassLoader.getResources("META-INF/services/...")}, which delegates to
 * {@code getResource()} for each classpath entry. A null return from the first call causes the
 * Enumeration to be empty; {@code ServiceLoader.iterator()} returns an empty iterator;
 * {@code ServiceLoader.findFirst()} returns {@code Optional.empty()}. Applications that treat
 * a missing service provider as fatal must check the iterator before use.
 *
 * <p>Spring's {@code SpringFactoriesLoader} (pre-Spring 6) reads
 * {@code META-INF/spring.factories} via {@code getResources()}; Spring 6+ reads
 * {@code META-INF/spring/...} import files. A null return from both causes all factories
 * to be absent; Spring Boot's auto-configuration, test support factories, and extension
 * mechanisms are all driven by these files. Suppressing all resource loads is extremely
 * disruptive; use probability-based application to suppress only a fraction of lookups.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosResourceLoadSuppress
 * class MissingServiceProviderTest {
 *   @Test
 *   void missingServiceProviderIsDetectedAndReportedClearly(ConnectionInfo info) {
 *     // assert ServiceLoader finds no providers and application fails with clear message
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
 * @see ChaosResourceLoadDelay
 * @see ChaosClassLoadInjectException
 */
@Repeatable(ChaosResourceLoadSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.CLASS_LOADING,
    operationType = OperationType.RESOURCE_LOAD)
public @interface ChaosResourceLoadSuppress {

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
   * @ChaosResourceLoadSuppress(id = "primary",  probability = 0.001)
   * @ChaosResourceLoadSuppress(id = "replica",  probability = 0.01)
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
    ChaosResourceLoadSuppress[] value();
  }
}
