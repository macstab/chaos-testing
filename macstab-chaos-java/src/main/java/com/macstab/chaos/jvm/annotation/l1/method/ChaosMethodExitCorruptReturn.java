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
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Replaces the return value of every matched method with a synthetic corrupt value at method exit,
 * simulating silent data corruption or a misbehaving dependency that returns a legal but wrong
 * result.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive that targets application methods by name pattern and corrupts
 * their return value at exit — one typed annotation per (selector family, operation type, effect)
 * tuple. It is the return-value counterpart to {@link ChaosMethodEnterInjectException}: where that
 * annotation aborts the call with an exception, this one lets the method run to completion and then
 * silently substitutes the real return value with a synthetic one chosen by the configured {@link
 * com.macstab.chaos.jvm.api.ChaosEffect.ReturnValueStrategy}. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent scans every class loaded by the target JVM whose binary name starts with
 *       {@link #classPattern()} and instruments every method whose name starts with {@link
 *       #methodNamePattern()}.
 *   <li>The method body executes normally — all side effects, database writes, and state mutations
 *       take place as usual.
 *   <li>At the instrumented method exit — immediately before the return value is handed back to the
 *       caller — the interceptor discards the real return value and substitutes the value produced
 *       by the configured {@link #strategy()}:
 *       <ul>
 *         <li>{@code NULL} — returns {@code null} for reference types; {@code 0}/{@code false} for
 *             primitives.
 *         <li>{@code ZERO} — returns numeric zero, {@code false}, or {@code '\0'}.
 *         <li>{@code EMPTY} — returns an empty string, empty array, or empty collection if the
 *             return type supports it; falls back to {@code null} otherwise.
 *         <li>{@code BOUNDARY_MAX} — returns {@code Integer.MAX_VALUE}, {@code Long.MAX_VALUE},
 *             etc. for numeric types; for reference types falls back to {@code null}.
 *         <li>{@code BOUNDARY_MIN} — returns the corresponding minimum boundary value.
 *       </ul>
 *   <li>The caller receives the synthetic value and continues execution, unaware that the real
 *       value was discarded.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Null pointer propagation.</strong> When {@code NULL} is applied to a method that
 *       returns an object, every caller that dereferences the result without a null check will
 *       throw {@code NullPointerException}; assert that the NPE is caught at a boundary and
 *       converted to a safe error response rather than propagating to the client.
 *   <li><strong>Numeric overflow or underflow.</strong> {@code BOUNDARY_MAX}/{@code BOUNDARY_MIN}
 *       applied to a method that returns a count, index, or price exposes arithmetic assumptions;
 *       assert that the consumer of that value guards against overflow (e.g. that a shopping-cart
 *       total does not silently wrap around to a negative price).
 *   <li><strong>Empty collection starvation.</strong> Returning an empty list from a data-fetching
 *       method causes any loop or stream over the result to short-circuit immediately; assert that
 *       the UI or downstream processor handles zero-result responses rather than displaying a blank
 *       page without explanation.
 *   <li><strong>Silent data loss.</strong> The method body ran and committed state (e.g. a database
 *       write succeeded), but the caller received {@code null}; assert that the caller does not
 *       assume failure and retry the write, causing a duplicate.
 *   <li><strong>Production failure mode:</strong> a buggy proxy or cache layer that returns a
 *       cached {@code null} on a miss, or a serialisation bug that deserialises a field as zero,
 *       produces exactly this pattern — the real operation completed but the caller sees a corrupt
 *       result.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The intercept point is {@code METHOD_EXIT} — the logical exit of the method frame, after the
 * last user bytecode has executed and the return value is on the operand stack. Byte Buddy inserts
 * an {@code @Advice.OnMethodExit} interceptor that reads the return value, discards it, and pushes
 * the synthetic value of the correct type before the {@code return} bytecode executes. The
 * substitution is type-safe: the agent inspects the method's return type descriptor to produce a
 * value of the correct JVM type.
 *
 * <p>For {@code void} methods the annotation has no effect because there is no return value to
 * corrupt; the agent silently skips void methods even if they match the patterns. For methods that
 * declare a checked exception, the corruption still fires on the normal return path; if the method
 * throws instead of returning, the interceptor does not fire (exception propagation is unaffected).
 *
 * <p>Unlike {@link ChaosMethodEnterInjectException}, this annotation lets the full method body
 * execute. This means all side effects — writes to databases, calls to external services, updates
 * to in-memory state — occur before the return value is discarded. Tests must account for the fact
 * that the application's state is already changed even though the caller received a corrupt result;
 * this is the key difference from an exception-injection scenario and makes this annotation
 * particularly useful for testing idempotency and at-most-once delivery guarantees.
 *
 * <p>Pattern matching uses prefix semantics identical to {@link ChaosMethodEnterInjectException}:
 * {@code classPattern = "com.example.repo"} matches all classes in that package and its
 * sub-packages. Both patterns must be set carefully to avoid corrupting framework-internal methods
 * that share the package prefix.
 *
 * <p>Combining a return-corruption rule with a heap or GC pressure stressor on the same test class
 * is valid; the agent merges all active rules into a single {@code ChaosPlan}. The combination
 * exercises whether the caller's null-handling code is correct even when the JVM is under memory
 * pressure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosMethodExitCorruptReturn(
 *     classPattern = "com.example.inventory.InventoryService",
 *     methodNamePattern = "getAvailableQuantity",
 *     strategy = ChaosEffect.ReturnValueStrategy.ZERO)
 * class InventoryZeroQuantityTest {
 *   @Test
 *   void checkoutHandlesOutOfStockGracefully(ConnectionInfo info) { ... }
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
@Repeatable(ChaosMethodExitCorruptReturn.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ReturnValueCorruptionTranslator")
@JvmMethodBinding(operationType = OperationType.METHOD_EXIT)
public @interface ChaosMethodExitCorruptReturn {

  /**
   * @return prefix matched against the binary class name
   */
  String classPattern() default "";

  /**
   * @return prefix matched against the method name
   */
  String methodNamePattern() default "";

  /**
   * @return strategy for the substituted return value (NULL / ZERO / EMPTY / BOUNDARY_MAX /
   *     BOUNDARY_MIN)
   */
  ChaosEffect.ReturnValueStrategy strategy() default ChaosEffect.ReturnValueStrategy.NULL;

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
   * @ChaosMethodExitCorruptReturn(id = "primary",  probability = 0.001)
   * @ChaosMethodExitCorruptReturn(id = "replica",  probability = 0.01)
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
    ChaosMethodExitCorruptReturn[] value();
  }
}
