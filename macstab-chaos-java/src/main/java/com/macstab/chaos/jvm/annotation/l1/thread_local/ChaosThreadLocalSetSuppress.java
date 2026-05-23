/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.thread_local;

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
 * Intercepts {@code ThreadLocal.set()} and silently discards the value without storing it in the
 * {@code Thread.threadLocals} map, simulating a failed context propagation that leaves Spring
 * transaction bindings, Hibernate session holders, security contexts, and SLF4J MDC absent from
 * the thread even after the framework attempts to install them.
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
 *   <li>Before every call to {@code java.lang.ThreadLocal#set(T)} inside the target container's
 *       JVM, the chaos agent intercepts the calling thread.
 *   <li>The agent returns without calling the real {@code set()}; the value is not stored in the
 *       {@code Thread.threadLocals} map; any previous value for the same {@code ThreadLocal}
 *       remains unchanged.
 *   <li>Subsequent calls to {@code ThreadLocal.get()} on the same instance return whatever was
 *       stored before the suppressed set (or {@code null} if nothing was previously stored).
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Spring's {@code TransactionSynchronizationManager.bindResource(Object key, Object value)}
 *       calls {@code ThreadLocal.set()} to bind a datasource connection to the current thread;
 *       with suppress active, the binding does not happen; the next call to
 *       {@code getResource(key)} returns {@code null}; Spring creates a new connection for each
 *       repository call instead of reusing the transaction's connection; assert that multiple
 *       repository calls within a {@code @Transactional} method do not execute in separate
 *       database transactions.
 *   <li>Spring Security's {@code SecurityContextHolder.setContext(SecurityContext)} stores the
 *       security context via a {@code ThreadLocal}; a suppressed set means the security context
 *       is lost immediately after being set; downstream calls to {@code getAuthentication()} return
 *       {@code null}; assert that the application correctly returns HTTP 401 or 403 rather than
 *       NullPointerException.
 *   <li>SLF4J's {@code MDC.put(key, value)} stores keys in a {@code ThreadLocal<Map>}; a
 *       suppressed set means MDC keys are never stored; log events lack correlation IDs; assert
 *       that the monitoring system flags the missing trace context.
 *   <li><strong>Production failure mode:</strong> a reactive framework's context propagation
 *       mechanism uses a library that bridges reactive context to {@code ThreadLocal} via
 *       {@code ThreadLocal.set()} at the start of each operator; if the bridge is incorrectly
 *       configured (e.g. wrong {@code Context} key), the set calls complete normally but the
 *       wrong value is stored — a different failure than suppression, but with the same downstream
 *       observable effect: thread-local-dependent libraries see wrong or absent context.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.lang.ThreadLocal#set(T)} and performs a no-op instead
 * of the real set. The JVM's {@code ThreadLocal.set()} calls
 * {@code Thread.currentThread().threadLocals} (a {@code ThreadLocal.ThreadLocalMap}) and either
 * updates the existing entry for the given {@code ThreadLocal} instance or creates a new entry.
 * With suppress active, neither update nor creation happens; the map state after the intercept
 * is identical to the state before the intercepted set call.
 *
 * <p>Spring's {@code TransactionSynchronizationManager} stores six pieces of per-transaction state
 * in separate {@code ThreadLocal} fields. If the set for the {@code resources} field is suppressed,
 * Spring cannot bind the datasource connection to the thread; all subsequent connection lookups
 * return {@code null}; {@code DataSourceUtils.getConnection()} opens a new connection on every
 * call; the application exhausts the connection pool without any single method holding more than
 * one connection — a hard-to-diagnose pool exhaustion scenario.
 *
 * <p>This annotation and {@link ChaosThreadLocalGetSuppress} are complementary: set-suppress
 * tests whether the application correctly handles the case where context propagation fails at
 * the moment of propagation; get-suppress tests whether it handles the case where the context
 * disappears after it was propagated. The former simulates a propagation bug; the latter simulates
 * a context loss bug.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadLocalSetSuppress
 * class SecurityContextPropagationTest {
 *   @Test
 *   void missingSecurityContextCausesUnauthorizedResponse(ConnectionInfo info) {
 *     // assert HTTP 401 or 403 is returned rather than NullPointerException
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
 * @see ChaosThreadLocalGetSuppress
 * @see ChaosThreadLocalGetDelay
 */
@Repeatable(ChaosThreadLocalSetSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.THREAD_LOCAL,
    operationType = OperationType.THREAD_LOCAL_SET)
public @interface ChaosThreadLocalSetSuppress {

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
   * @ChaosThreadLocalSetSuppress(id = "primary",  probability = 0.001)
   * @ChaosThreadLocalSetSuppress(id = "replica",  probability = 0.01)
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
    ChaosThreadLocalSetSuppress[] value();
  }
}
