/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.jvm_runtime;

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
 * Throws a configurable exception (typically {@link javax.naming.NamingException}) at every
 * {@code InitialContext.lookup()} call site, simulating a misconfigured or unavailable JNDI
 * naming tree.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code JNDI_LOOKUP} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code javax.naming.InitialContext.lookup(String)}
 *       in the target container's JVM.
 *   <li>Before the naming provider is consulted, the interceptor instantiates the exception class
 *       named by {@link #exceptionClassName()} with {@link #message()} and throws it.
 *   <li>The calling thread unwinds from the throw site; the named object is never returned.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>DataSource acquisition fails.</strong> An application that looks up its data source
 *       via JNDI will fail to connect to the database; assert that the application server either
 *       retries the lookup or rejects incoming requests with an appropriate 503.
 *   <li><strong>EJB lookup fails.</strong> Remote EJB references that are resolved lazily will
 *       throw at the point of first use; assert that the caller handles the exception and does not
 *       propagate an internal EJB exception to the API consumer.
 *   <li><strong>JMS provider connection impossible.</strong> Message-driven beans and JMS consumers
 *       will fail to acquire a connection factory; assert that the consumer stops gracefully rather
 *       than crash-looping.
 *   <li><strong>Production failure mode:</strong> in cloud environments where JNDI is backed by an
 *       external naming service (e.g. Consul, etcd, LDAP), a naming service outage causes every
 *       JNDI lookup to throw, which can prevent the application from acquiring its resources and
 *       effectively cause a cold-start failure even though the application binaries are healthy.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The natural exception type for JNDI failures is {@code javax.naming.NamingException} or one
 * of its subclasses: {@code NameNotFoundException} (name not bound), {@code CommunicationException}
 * (provider unreachable), or {@code AuthenticationException} (bad credentials). Each subclass
 * exercises a different branch of the application's exception handler and should be selected
 * according to the scenario being tested.
 *
 * <p>The agent throws the exception before the naming provider processes the request, so no
 * provider-side state is changed. The JNDI {@code InitialContext} object remains valid after the
 * throw; subsequent lookup calls on the same context will also be intercepted and thrown. Tests
 * should verify that the application does not attempt to cache a failed lookup result and does not
 * retry without proper backoff.
 *
 * <p>JNDI is a thread-safe API but the exception is thrown on the calling thread only. If the
 * application uses a thread pool to issue concurrent JNDI lookups (e.g. during parallel connection
 * pool initialisation), each thread will receive an independent exception. The chaos therefore
 * exercises concurrent exception handling, not just sequential error handling.
 *
 * <p>In Spring applications, JNDI lookups are often wrapped in a {@code JndiTemplate} that
 * converts {@code NamingException} to a {@code DataAccessException}; assert that this conversion
 * is visible in the application's error response and logs.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJndiLookupInjectException(
 *     exceptionClassName = "javax.naming.CommunicationException",
 *     message = "JNDI provider unreachable")
 * class JndiFailureTest {
 *   @Test
 *   void applicationRejectsRequestsGracefullyWhenJndiUnavailable(ConnectionInfo info) { ... }
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
@Repeatable(ChaosJndiLookupInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.JNDI_LOOKUP)
public @interface ChaosJndiLookupInjectException {

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
   * @ChaosJndiLookupInjectException(id = "primary",  probability = 0.001)
   * @ChaosJndiLookupInjectException(id = "replica",  probability = 0.01)
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
    ChaosJndiLookupInjectException[] value();
  }
}
