/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.async;

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
 * Parks the calling thread for {@link #delayMs} to {@link #maxDelayMs} milliseconds inside every
 * {@link java.util.concurrent.CompletableFuture#cancel CompletableFuture.cancel(mayInterrupt)}
 * call, increasing the wall-clock time of all cancellation paths without suppressing or replacing
 * the operation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code ASYNC} selector family targeting the {@code
 * ASYNC_CANCEL} operation with the {@code delay} effect. It intercepts every call to {@code
 * CompletableFuture.cancel} in the container JVM and parks the calling thread for a configurable
 * duration before allowing the cancellation to proceed normally. The future is eventually cancelled
 * as it would be without chaos — the delay only stretches the time window between the cancellation
 * request and the cancellation taking effect.
 *
 * <p>This is distinct from {@link ChaosAsyncCancelSuppress}, which discards the cancel call
 * entirely. Here the future <em>is</em> cancelled, but later than expected.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code CompletableFuture.cancel}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the calling thread before {@code cancel}'s body executes.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code
 *       [delayMs, maxDelayMs]} when {@code maxDelayMs > delayMs}), parking the calling thread for
 *       the configured duration.
 *   <li>After the sleep, control returns to the original {@code cancel} body, which executes
 *       normally: the future's CAS transitions from {@code PENDING} to {@code CANCELLED}, waiting
 *       threads are unparked, and {@code cancel} returns {@code true} (or {@code false} if the
 *       future was already done).
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code cf.cancel(true)} returns {@code true} — the cancellation does succeed, just later.
 *   <li>{@code cf.isCancelled()} returns {@code true} after the call returns.
 *   <li>Callers that expect cancellation to be near-instant will observe a latency spike of at
 *       least {@link #delayMs} ms on every cancel path.
 *   <li>Timeout-guarded cancel calls (e.g. in shutdown hooks or reactive unsubscribe paths) may
 *       themselves time out if {@link #delayMs} exceeds the caller's deadline.
 *   <li>Any thread joining on the future's result between the cancel call and the delayed
 *       completion will observe the future remaining in the {@code PENDING} state for the duration
 *       of the sleep.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a reactive pipeline's unsubscribe path or a
 * graceful-shutdown handler calls {@code cancel} on a batch of in-flight futures, but the
 * cancellation logic is slower than the surrounding timeout — the service exits leaving futures
 * silently hanging, causing resource leaks and unexpected callbacks after restart.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets
 * {@code java.util.concurrent.CompletableFuture#cancel(boolean)} via a Byte Buddy method
 * interceptor installed in the premain phase. Because {@code CompletableFuture} is a JDK bootstrap
 * class, the agent uses retransformation over the bootstrap class loader's channel. If the JVM has
 * already loaded {@code CompletableFuture} before the agent attaches, the agent retransforms it
 * in-place without reloading the class.
 *
 * <p><strong>Thread parking mechanics.</strong> The delay is implemented as {@code Thread.sleep},
 * which issues an OS-level park on the calling thread. During the sleep the thread holds no
 * monitor locks, so no deadlock risk is introduced by the delay itself. However, if the calling
 * thread is a virtual thread (Project Loom, Java 21+), the sleep yields the carrier thread back
 * to the scheduler — the delay still fires but does not block a platform thread for its duration.
 *
 * <p><strong>Cascading effects.</strong> Any code that holds a lock before calling {@code cancel}
 * continues holding that lock for the entire sleep duration. If other threads try to acquire the
 * same lock, they will queue behind the sleeping thread, amplifying the latency effect across the
 * application. Shutdown sequences that cancel multiple futures sequentially accumulate one sleep
 * per future, potentially stretching a graceful-shutdown window from milliseconds to seconds.
 *
 * <p><strong>Interaction with mayInterruptIfRunning.</strong> The {@code mayInterrupt} flag is
 * passed unchanged to the original {@code cancel} body after the sleep. Any thread that is
 * blocked inside the future's computation and would be interrupted by {@code cancel(true)} is only
 * interrupted after the delay, widening the window during which the computation thread consumes
 * CPU or holds other resources.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosAsyncCancelSuppress} discards the
 * cancel — the future is never cancelled. This annotation preserves correctness but stretches
 * timing. To simulate a cancellation that completes the future with an exception rather than
 * cancelling it, there is no direct L1 primitive; compose with
 * {@link ChaosAsyncCompleteExceptionalCompletion} on the complete path instead.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosAsyncCancelDelay(delayMs = 500, maxDelayMs = 1500)
 * class SlowCancellationTest {
 *
 *   @Test
 *   void shutdownHandlerTimesOutDuringSlowCancel(AppConnectionInfo info) throws Exception {
 *     CompletableFuture<String> inflight = client.startLongOperation(info);
 *     long start = System.currentTimeMillis();
 *     inflight.cancel(true);
 *     long elapsed = System.currentTimeMillis() - start;
 *     assertThat(inflight.isCancelled()).isTrue();
 *     assertThat(elapsed).isGreaterThanOrEqualTo(500L);
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#ASYNC_CANCEL
 * @see com.macstab.chaos.jvm.api.ChaosSelector#async(java.util.Set)
 * @see ChaosAsyncCancelSuppress
 */
@Repeatable(ChaosAsyncCancelDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.ASYNC,
    operationType = OperationType.ASYNC_CANCEL)
public @interface ChaosAsyncCancelDelay {

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
   * @ChaosAsyncCancelDelay(id = "primary",  probability = 0.001)
   * @ChaosAsyncCancelDelay(id = "replica",  probability = 0.01)
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
    ChaosAsyncCancelDelay[] value();
  }
}
