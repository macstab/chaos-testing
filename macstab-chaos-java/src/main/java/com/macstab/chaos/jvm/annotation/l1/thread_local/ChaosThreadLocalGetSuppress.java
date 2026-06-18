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
 * Intercepts {@code ThreadLocal.get()} and returns {@code null} without performing the {@code
 * Thread.threadLocals} map lookup, simulating a cleared or absent thread-local value that causes
 * Spring transaction bindings, Hibernate session holders, and SLF4J MDC context to appear absent to
 * framework code that depends on them.
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
 *   <li>Before every call to {@code java.lang.ThreadLocal#get()} inside the target container's JVM,
 *       the chaos agent intercepts the calling thread.
 *   <li>The agent returns {@code null} immediately without performing the {@code ThreadLocalMap}
 *       lookup; the stored value is not returned even if it was previously set.
 *   <li>The caller receives {@code null} as if the thread-local had never been set or had been
 *       removed via {@code ThreadLocal.remove()}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Spring's {@code TransactionSynchronizationManager.getResource(Object key)} calls {@code
 *       ThreadLocal.get()} on its {@code resources} map; with suppress active it returns {@code
 *       null}; Spring's {@code DataSourceUtils.getConnection()} interprets this as "no active
 *       transaction" and acquires a new non-transactional connection, silently breaking
 *       transactional guarantees; assert that this is detected as an error and not silently
 *       committed outside the transaction boundary.
 *   <li>Hibernate's {@code SpringSessionContext.currentSession()} uses Spring's {@code
 *       TransactionSynchronizationManager} to look up the current session; a {@code null} return
 *       causes {@code HibernateException: No Session found for current thread}; assert that the
 *       application's JPA layer surfaces this as a clear error rather than a NullPointerException
 *       deep in the Hibernate call stack.
 *   <li>SLF4J's MDC ({@code MDC.getCopyOfContextMap()}) returns {@code null} when the underlying
 *       {@code ThreadLocal<Map>} is suppressed; log events lose their correlation IDs; assert that
 *       the monitoring system detects missing trace context and raises an alert.
 *   <li><strong>Production failure mode:</strong> a thread pool reuses threads but does not call
 *       {@code ThreadLocal.remove()} after task completion; values from a previous task's context
 *       leak into the next task; a future refactoring adds a remove call that over-removes the
 *       value before a downstream library reads it; the library silently receives {@code null} and
 *       falls back to default behaviour — the same symptom that this annotation exercises.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.lang.ThreadLocal#get()} and returns {@code null} without
 * accessing the {@code Thread.threadLocals} map. The actual stored value (if any) remains in the
 * map — it is not cleared — but it cannot be retrieved while the suppress effect is active. After
 * the fault window closes, subsequent {@code get()} calls return the previously stored value
 * normally, as the map entry was never removed.
 *
 * <p>This is distinct from {@link ChaosThreadLocalSetSuppress}: set-suppress prevents new values
 * from being stored; get-suppress prevents existing values from being read. Combining both creates
 * a completely broken thread-local that appears permanently absent: new sets are ignored and
 * existing gets return {@code null} — the application behaves as if the thread has never
 * participated in any thread-local-based context propagation.
 *
 * <p>Spring's {@code AbstractPlatformTransactionManager.getTransaction()} accesses multiple
 * thread-local fields via {@code TransactionSynchronizationManager}; if any of these return {@code
 * null} unexpectedly, the transaction manager may start a new transaction when it should join an
 * existing one, effectively splitting a logical transaction across two physical database
 * transactions. This is a silent data-consistency bug that only manifests as incorrect
 * read-your-writes behaviour.
 *
 * <p>Libraries that use {@code ThreadLocal} with a fallback to a default value ({@code if (value ==
 * null) return DEFAULT}) will silently use the default; libraries that throw on {@code null}
 * ({@code Objects.requireNonNull(threadLocal.get())}) will throw immediately. Both paths must be
 * tested, and this annotation enables both scenarios without modifying application code.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadLocalGetSuppress
 * class TransactionContextLostTest {
 *   @Test
 *   void missingTransactionContextCausesHibernateSessionException(ConnectionInfo info) {
 *     // assert HibernateException: No Session found for current thread
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
 * @see ChaosThreadLocalSetSuppress
 * @see ChaosThreadLocalGetDelay
 */
@Repeatable(ChaosThreadLocalGetSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.THREAD_LOCAL,
    operationType = OperationType.THREAD_LOCAL_GET)
public @interface ChaosThreadLocalGetSuppress {

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
   * @ChaosThreadLocalGetSuppress(id = "primary",  probability = 0.001)
   * @ChaosThreadLocalGetSuppress(id = "replica",  probability = 0.01)
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
    ChaosThreadLocalGetSuppress[] value();
  }
}
