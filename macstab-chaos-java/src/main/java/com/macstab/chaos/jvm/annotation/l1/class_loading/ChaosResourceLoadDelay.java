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
 * holds the calling thread for {@link #delayMs()} milliseconds before the resource URL is
 * resolved, simulating slow classpath scanning or a high-latency JAR file system when Spring,
 * Hibernate, or any framework resolves configuration resources from the classpath.
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
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code getResource()} executes normally, scanning the
 *       classpath entries (JAR files, directories) to locate the resource and return its URL.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Spring Boot's auto-configuration loading calls {@code getResource()} to locate
 *       {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *       files in each JAR on the classpath; the delay fires for each JAR lookup; on a classpath
 *       with 200 JARs the total delay inflates startup time by {@code 200 × delayMs}; assert
 *       that the readiness probe timeout accounts for this.
 *   <li>Hibernate's {@code MetadataSources} scans for {@code hbm.xml} mapping files and JPA
 *       XML configuration via {@code getResourceAsStream()}; the delay inflates the EntityManagerFactory
 *       initialisation time; assert that the JPA timeout is configured appropriately.
 *   <li>Log4j's configuration loading uses {@code ClassLoader.getResource("log4j2.xml")} to
 *       locate the configuration file; a delay here extends the time between JVM startup and
 *       the first log statement being processed; assert that the application does not lose log
 *       events emitted before the configuration is loaded.
 *   <li><strong>Production failure mode:</strong> an application built with a large number of
 *       nested fat-JAR files (Spring Boot's nested JAR layout) uses a custom
 *       {@code LaunchedURLClassLoader} that scans through nested JAR entries on each resource
 *       lookup; under memory pressure, the JVM evicts the JAR index from the page cache; each
 *       resource lookup requires re-reading and re-parsing JAR central directory entries, making
 *       {@code getResource()} noticeably slow; the application's startup time increases in
 *       proportion to heap pressure.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.lang.ClassLoader#getResource(String)} and
 * {@code ClassLoader#getResourceAsStream(String)}. Both follow the same delegation model as
 * {@code loadClass()}: the parent class loader is consulted first. The chaos delay fires before
 * the parent delegation, adding to the time for each step of the hierarchy traversal.
 *
 * <p>Spring's {@code PathMatchingResourcePatternResolver} uses {@code getResources()} (plural)
 * which calls {@code getResource()} for each candidate path. Classpath scanning with a wildcard
 * pattern ({@code classpath*:META-INF/**}) calls {@code getResources()} once per JAR or directory
 * on the classpath; each call incurs the delay. A fat JAR with 200 classpath entries generates
 * 200 delay invocations for a single classpath scan.
 *
 * <p>JAXB's {@code JAXBContext.newInstance()} calls {@code ClassLoader.getResource("jaxb.index")}
 * and {@code getResourceAsStream("javax/xml/bind/...")}) to locate implementation classes; the
 * delay fires here during JAXB context initialisation. Spring's XML-based configuration loading
 * uses {@code getResourceAsStream()} to read XML files; the delay fires once per XML file.
 *
 * <p>The delay models realistic classpath resource lookup latency that occurs when the JVM's
 * file metadata cache is cold (e.g. after a cold deployment on a new node) versus warm (after
 * the OS has cached the JAR directory entries). This is particularly relevant for applications
 * that deploy frequently and whose startup time SLO must account for cold-cache scenarios.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosResourceLoadDelay(delayMs = 10)
 * class ClasspathScanningDelayTest {
 *   @Test
 *   void startupToleratesColdClasspathResourceLookup(ConnectionInfo info) {
 *     // assert application becomes ready within the configured readiness probe timeout
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
 * @see ChaosResourceLoadSuppress
 * @see ChaosClassLoadDelay
 */
@Repeatable(ChaosResourceLoadDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.CLASS_LOADING,
    operationType = OperationType.RESOURCE_LOAD)
public @interface ChaosResourceLoadDelay {

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
   * @ChaosResourceLoadDelay(id = "primary",  probability = 0.001)
   * @ChaosResourceLoadDelay(id = "replica",  probability = 0.01)
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
    ChaosResourceLoadDelay[] value();
  }
}
