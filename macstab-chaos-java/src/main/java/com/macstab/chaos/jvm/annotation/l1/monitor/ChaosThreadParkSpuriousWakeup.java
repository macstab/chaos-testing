/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.monitor;

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
 * Causes {@link java.util.concurrent.locks.LockSupport#park(Object)} to return immediately without
 * being unparked — every call site that parks to wait for a condition sees a spurious wakeup, as if
 * the OS had prematurely woken the thread with no signal.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code MONITOR} selector family with the {@code
 * spuriousWakeup} effect applied to the {@code THREAD_PARK} operation. It intercepts {@code
 * LockSupport.park(Object)} and skips the actual park, causing the method to return immediately
 * with no blocker signal having been received. This exercises the re-check loop that all correct
 * {@code park}-based synchronisers must implement to guard against spurious OS wakeups. The
 * annotation is declared on the test class or method alongside a container annotation and is active
 * for the lifetime of the annotated scope.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code LockSupport.park(Object)}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the native park stub executes.
 *   <li>The spurious-wakeup effect skips the park entirely — the native stub is not called.
 *   <li>{@code LockSupport.park} returns {@code void} to the caller immediately, as if the OS had
 *       delivered a spurious POSIX signal that unblocked the thread with no actual wakeup reason.
 *   <li>The caller's thread state was never WAITING; the blocker object is never set and cleared in
 *       the JVM's thread structure.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>AQS-based operations that require the predecessor to release the lock loop back to their
 *       condition check and re-park; assert that the operation still completes correctly (proving
 *       the re-check loop is implemented).
 *   <li>Incorrectly written code that does not wrap {@code park} in a condition loop busy-waits at
 *       CPU speed; assert that CPU consumption of the container spikes when this annotation is
 *       active.
 *   <li>{@code CompletableFuture.get()} re-enters its internal spin-then-park loop on each spurious
 *       wakeup; the future eventually completes normally once the real completion arrives — assert
 *       that the future completes correctly even under spurious wakeup injection.
 *   <li>Timed waits ({@code parkNanos}) return early without consuming the full timeout; assert
 *       that timed operations do not treat spurious early return as a timeout.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a Linux kernel delivering spurious
 * futex wakeups under high memory pressure — a {@code ReentrantLock} node is woken before its
 * predecessor releases the lock; if the AQS re-check loop is missing or broken (e.g. due to an
 * incorrect double-checked locking pattern), the thread acquires the lock prematurely and corrupts
 * shared state, producing a race condition that is otherwise extremely rare.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> {@code LockSupport.park(Object)} is instrumented via the
 * bootstrap class loader channel. The spurious-wakeup effect is identical in observable behaviour
 * to calling {@code LockSupport.unpark(Thread.currentThread())} just before the park — the permit
 * is consumed and the park returns immediately. However, the implementation skips the park entirely
 * rather than issuing an unpark, so the thread's park-permit counter is not touched and subsequent
 * parks are not pre-consumed.
 *
 * <p><strong>AQS and the re-check loop.</strong> All correct AQS-based synchronisers call {@code
 * park} inside a {@code while (!condition)} loop. A spurious wakeup causes the loop to evaluate the
 * condition and re-park if the condition is still false. This annotation therefore exercises the
 * correctness of that loop without requiring a real concurrent unpark. Code that uses {@code if}
 * instead of {@code while} around {@code park} will misbehave and pass through the critical section
 * prematurely.
 *
 * <p><strong>{@code parkNanos} and {@code parkUntil}.</strong> These timed variants are also
 * intercepted. A spurious wakeup from a timed park cannot be distinguished from a normal timeout by
 * the caller (both return void). Callers must check the condition or the remaining time to
 * determine which occurred. Buggy code that assumes a timed-park return implies timeout will
 * misidentify spurious wakeups as timeouts, silently abandoning waits prematurely.
 *
 * <p><strong>Distinction from {@code ChaosThreadParkDelay} and {@code
 * ChaosThreadParkGate}.</strong> The delay effect adds time before the real park. The gate effect
 * holds the thread indefinitely. The spurious-wakeup effect skips the park entirely, causing the
 * caller to receive an immediate return indistinguishable from a real wakeup — it tests whether the
 * caller re-checks its condition rather than proceeding blindly.
 *
 * <p><strong>Virtual-thread interaction.</strong> Virtual threads park via the same {@code
 * LockSupport.park} path. A spurious wakeup on a virtual thread causes the virtual thread to
 * continue executing on the carrier without yielding. Under heavy virtual-thread concurrency, many
 * threads spinning through spurious-wakeup loops can saturate the carrier pool.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadParkSpuriousWakeup
 * class SpuriousWakeupTest {
 *
 *   @Test
 *   void futureCompletesCorrectlyUnderSpuriousWakeups(AppConnectionInfo info) throws Exception {
 *     CompletableFuture<String> result = client.fetchAsync(info);
 *     // spurious wakeups on get()'s internal park must not corrupt the result
 *     assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("expected-value");
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Required:</strong>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code
 *       beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#THREAD_PARK
 * @see com.macstab.chaos.jvm.api.ChaosSelector#monitor(java.util.Set)
 * @see ChaosThreadParkDelay
 * @see ChaosThreadParkGate
 */
@Repeatable(ChaosThreadParkSpuriousWakeup.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SpuriousWakeupTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.MONITOR,
    operationType = OperationType.THREAD_PARK)
public @interface ChaosThreadParkSpuriousWakeup {

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
   * @ChaosThreadParkSpuriousWakeup(id = "primary",  probability = 0.001)
   * @ChaosThreadParkSpuriousWakeup(id = "replica",  probability = 0.01)
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
    ChaosThreadParkSpuriousWakeup[] value();
  }
}
