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
 * Delays every {@code InitialContext.lookup()} call by a configurable number of milliseconds,
 * simulating a slow or overloaded JNDI naming service.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code JNDI_LOOKUP} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code javax.naming.InitialContext.lookup(String)}
 *       in the target container's JVM.
 *   <li>Before forwarding the lookup to the naming provider, the interceptor parks the calling
 *       thread for a duration sampled uniformly between {@link #delayMs()} and {@link
 *       #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real lookup executes and the bound object is returned to the caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>DataSource acquisition slow at startup.</strong> Java EE and Jakarta EE
 *       applications look up data sources via JNDI during the first request or at deployment;
 *       assert that the application server starts within its configured deployment timeout.
 *   <li><strong>EJB remote lookup slow.</strong> Remote EJB clients use JNDI to resolve remote
 *       references; assert that the client's connection timeout is applied correctly.
 *   <li><strong>JMS connection factory slow.</strong> JMS providers are often bound to JNDI; a slow
 *       lookup delays message-consumer startup and can cause producer timeouts if the consumer is
 *       not ready within the broker's session timeout.
 *   <li><strong>Production failure mode:</strong> in legacy application servers (WebLogic,
 *       WebSphere) JNDI lookups may contact a remote cluster naming service over the network; a
 *       network partition causes the lookup to hang until the configured JNDI provider timeout,
 *       blocking the calling thread and potentially the entire connection pool.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code InitialContext.lookup()} resolves a JNDI name through the configured naming provider
 * (LDAP, RMI, file-system, in-JVM). For in-JVM providers (e.g. Tomcat's {@code org.apache.naming}
 * package) the lookup is fast and local; for remote providers (LDAP, JNDI-over-RMI) it involves a
 * network round-trip. The agent intercepts at the {@code InitialContext.lookup()} level regardless
 * of which provider is used.
 *
 * <p>The delay fires before any network I/O, so it does not interfere with the naming provider's
 * own timeout. The total latency observed by the caller is the injected delay plus the provider's
 * resolution time. Tests that are sensitive to the exact timeout boundary should account for both
 * components.
 *
 * <p>JNDI lookups in modern Jakarta EE applications are sometimes replaced by CDI injection or
 * Spring-managed beans. This annotation is therefore most relevant for legacy applications,
 * integration tests that spin up an embedded application server, and JMX-based management clients
 * that use JNDI to locate MBeans.
 *
 * <p>Combining this annotation with {@link ChaosJndiLookupInjectException} in a repeatable form
 * allows a test to first experience slow lookups (simulating provider overload) and then hard
 * failures (simulating provider unavailability), validating that the application's JNDI failure
 * handling covers both cases.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosJndiLookupDelay(delayMs = 2_000, maxDelayMs = 5_000)
 * class JndiDelayTest {
 *   @Test
 *   void datasourceAcquisitionTimeoutHandledCorrectly(ConnectionInfo info) { ... }
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
@Repeatable(ChaosJndiLookupDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.JNDI_LOOKUP)
public @interface ChaosJndiLookupDelay {

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
   * @ChaosJndiLookupDelay(id = "primary",  probability = 0.001)
   * @ChaosJndiLookupDelay(id = "replica",  probability = 0.01)
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
    ChaosJndiLookupDelay[] value();
  }
}
