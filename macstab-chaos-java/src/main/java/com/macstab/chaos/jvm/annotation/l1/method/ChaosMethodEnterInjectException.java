/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.method;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmMethodBinding;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Throws a configurable exception at every matched method entry point, providing a general-purpose
 * escape hatch for injecting failures into arbitrary application code without modifying sources.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive that targets application methods by name pattern rather than by
 * JDK API interception point — one typed annotation per (selector family, operation type, effect)
 * tuple. It is the escape hatch for scenarios where no dedicated interceptor annotation covers the
 * specific call site under test. Declared on a test class or {@code @Test} method, it is active
 * from {@code beforeAll}/{@code beforeEach} until {@code afterAll}/{@code afterEach} respectively.
 *
 * <p>Unlike the {@link com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding}-based annotations
 * (which target a fixed, named JDK interception point such as {@code ByteBuffer.allocateDirect()}
 * or {@code MBeanServer.invoke()}), this annotation uses a {@link
 * com.macstab.chaos.jvm.annotation.l1.JvmMethodBinding} with {@link #classPattern()} and {@link
 * #methodNamePattern()}: the agent intercepts every method whose declaring class name starts with
 * {@code classPattern} and whose simple name starts with {@code methodNamePattern}. Both patterns
 * are prefix-matched against the binary class name and the method name respectively; empty string
 * matches everything.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent scans every class loaded by the target JVM whose binary name starts with
 *       {@link #classPattern()} and instruments every method whose name starts with {@link
 *       #methodNamePattern()}.
 *   <li>At each instrumented method entry — before the first bytecode of the method body executes —
 *       the interceptor instantiates the exception class named by {@link #exceptionClassName()}
 *       with {@link #message()} as the detail message and throws it.
 *   <li>The calling thread unwinds from the throw site; the method body never runs.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Service method failures.</strong> Any application-layer method matching the pattern
 *       will throw on every call; assert that the caller (e.g. a REST controller, a service
 *       orchestrator) handles the exception without leaking internal state to the client.
 *   <li><strong>Repository / DAO failures.</strong> Targeting a repository prefix forces every
 *       database interaction to fail; assert that the service layer rolls back correctly and
 *       returns a meaningful error response rather than a partial write or a hung transaction.
 *   <li><strong>Framework callback failures.</strong> Injecting into lifecycle callbacks (e.g. a
 *       Spring {@code @PostConstruct} method) lets you verify that the container handles
 *       initialisation failures gracefully — for example, by marking the bean as unavailable or
 *       restarting the application context.
 *   <li><strong>Production failure mode:</strong> a transient dependency failure (network, disk,
 *       external API) manifests as an exception thrown from the first method that tries to contact
 *       the dependency; this annotation simulates exactly that entry-point failure without
 *       requiring the dependency to actually be broken.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The intercept point is {@code METHOD_ENTER} — the logical entry to the method frame, before
 * any user bytecode has executed. Byte Buddy inserts an interceptor at the method's entry prologue;
 * the interceptor checks whether the active chaos plan has a matching rule and, if so, instantiates
 * and throws the configured exception class. Because the throw occurs before the method body, no
 * local variables are initialised and no application state is mutated; the effect is a clean
 * invocation failure from the caller's perspective.
 *
 * <p>Pattern matching uses prefix semantics to keep the annotation concise. {@code classPattern =
 * "com.example.service"} matches {@code com.example.service.OrderService}, {@code
 * com.example.service.inventory.InventoryService}, and any other class whose binary name begins
 * with that string. Empty patterns match every class or method, which is generally too broad;
 * always set both patterns to constrain the injection scope to the intent of the test.
 *
 * <p>This annotation complements the dedicated JDK interception-point annotations: use the
 * dedicated annotations ({@code ChaosJndiLookupInjectException}, {@code
 * ChaosObjectDeserializeInjectException}, etc.) when you want to fail a specific JDK API and use
 * this annotation when you want to fail a specific application-level method regardless of what JDK
 * APIs that method calls internally. Combining both on the same test class is valid and results in
 * two independent chaos rules in the agent's active plan.
 *
 * <p>The exception class is loaded via the thread context class loader of the agent dispatch
 * thread. The class must therefore be on the application class path inside the container (not only
 * on the test class path). Standard JDK exception classes such as {@code java.io.IOException} or
 * {@code java.lang.RuntimeException} are always available. Custom application exception classes can
 * also be used, provided their binary name is given correctly and they have a single-argument
 * {@code String} constructor.
 *
 * <p>Because all matching methods are instrumented, broad patterns can cause unexpected failures in
 * framework-internal code that shares the same package prefix as application code. Scope the
 * patterns tightly to the specific class or method group under test, and use the class-scope vs
 * method-scope placement on the test to limit the window during which the injection is active.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosMethodEnterInjectException(
 *     classPattern = "com.example.service.PaymentService",
 *     methodNamePattern = "charge",
 *     exceptionClassName = "com.example.PaymentGatewayException",
 *     message = "gateway timeout simulated by chaos")
 * class PaymentServiceFaultTest {
 *   @Test
 *   void orderRollsBackOnPaymentGatewayTimeout(ConnectionInfo info) { ... }
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
@Repeatable(ChaosMethodEnterInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmMethodBinding(operationType = OperationType.METHOD_ENTER)
public @interface ChaosMethodEnterInjectException {

  /**
   * @return prefix matched against the binary class name (e.g. {@code "com.example.service"})
   */
  String classPattern() default "";

  /**
   * @return prefix matched against the method name (e.g. {@code "save"})
   */
  String methodNamePattern() default "";

  /**
   * @return binary class name of the exception to throw
   */
  String exceptionClassName() default "java.io.IOException";

  /**
   * @return exception message
   */
  String message() default "injected at METHOD_ENTER by chaos L1";

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
   * @ChaosMethodEnterInjectException(id = "primary",  probability = 0.001)
   * @ChaosMethodEnterInjectException(id = "replica",  probability = 0.01)
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
    ChaosMethodEnterInjectException[] value();
  }
}
