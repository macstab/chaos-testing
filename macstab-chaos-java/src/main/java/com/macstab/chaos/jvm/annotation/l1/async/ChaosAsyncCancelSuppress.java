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
 * Makes {@link java.util.concurrent.CompletableFuture#cancel
 * CompletableFuture.cancel(mayInterrupt)} silently return {@code false} without transitioning the
 * future to the cancelled state — tasks that the caller believes have been cancelled continue
 * running and may complete normally.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code ASYNC} selector family targeting the {@code
 * ASYNC_CANCEL} operation with the {@code suppress} effect. It intercepts every call to {@code
 * CompletableFuture.cancel} in the container JVM and discards the cancellation signal before the
 * future's internal state machine sees it. The future remains in whatever state it was in before
 * the call — pending, completed, or already cancelled — and the caller receives {@code false} as if
 * the future had already been done by another path.
 *
 * <p>This differs from {@link ChaosAsyncCancelDelay}, which allows the cancellation to happen but
 * stretches its latency. Here the cancellation <em>never happens at all</em>.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code CompletableFuture.cancel}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered before any of {@code cancel}'s body executes.
 *   <li>The suppress effect short-circuits the method: the cancel body is never run.
 *   <li>{@code cancel} returns {@code false} to the caller, indistinguishable from the normal
 *       return when the future was already completed by another thread.
 *   <li>The future's state is unchanged. No waiting threads are interrupted, no cancellation
 *       callbacks fire, and the underlying computation continues to run.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code cf.cancel(true)} returns {@code false} — the caller's cancellation request is
 *       silently rejected.
 *   <li>{@code cf.isCancelled()} returns {@code false} — the future is not in the cancelled state.
 *   <li>{@code cf.isDone()} returns {@code false} if the future was still pending — the future
 *       continues to hang indefinitely unless the underlying computation completes on its own.
 *   <li>Any thread blocked on {@code cf.get()} is never interrupted by {@code mayInterruptIfRunning
 *       = true}; it either blocks forever, times out, or unblocks only when the computation
 *       eventually calls {@code complete} or {@code completeExceptionally}.
 *   <li>Cancellation-aware code that checks {@code cancel}'s return value and falls back to a
 *       force-stop path will silently skip that fallback.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a timeout handler calls {@code cancel} on an
 * in-flight RPC future to stop a slow dependency, but the cancellation is silently dropped — the
 * RPC thread continues consuming resources and eventually delivers its result to a caller that has
 * already moved on, causing race conditions in state machines that assumed the task was stopped.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code
 * java.util.concurrent.CompletableFuture#cancel(boolean)} via a Byte Buddy method interceptor
 * installed in the premain phase. {@code CompletableFuture} is a JDK bootstrap class; the agent
 * retransforms it in-place. All instances in the JVM are affected — there is no per-instance filter
 * at the L1 primitive level.
 *
 * <p><strong>CAS and state machine bypass.</strong> {@code CompletableFuture.cancel} internally
 * calls {@code completeExceptionally(new CancellationException())} or performs an equivalent CAS on
 * the result field. By returning early without executing the body, the suppress effect ensures this
 * CAS never runs. The future's {@code result} field stays {@code null} (pending) and no {@code
 * CancellationException} is stored.
 *
 * <p><strong>Cascading effects.</strong> Any code path that relies on cancellation to stop a chain
 * of dependent computations will find the chain intact and still running after the suppressed call.
 * If {@code thenApply} / {@code thenCompose} stages were waiting for the parent to cancel, they
 * remain pending. Structured shutdown sequences that fire-and-forget a batch of {@code cancel}
 * calls will leave all affected futures running.
 *
 * <p><strong>Interaction with mayInterruptIfRunning.</strong> Because the body of {@code cancel}
 * never runs, the {@code mayInterrupt} flag has no effect. Threads blocked inside the future's
 * computation that would normally receive an {@code InterruptedException} continue undisturbed.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosAsyncCancelDelay} cancels correctly
 * but slowly. {@link ChaosAsyncCompleteExceptionalCompletion} on the {@code ASYNC_COMPLETE}
 * operation replaces a successful completion with an exception — a different axis of fault
 * injection. This annotation is the only one that leaves a pending future permanently un-cancelled
 * after an explicit cancel attempt.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosAsyncCancelSuppress
 * class CancellationIgnoredTest {
 *
 *   @Test
 *   void taskContinuesAfterCancelIsDropped(AppConnectionInfo info) throws Exception {
 *     CompletableFuture<String> inflight = client.startLongOperation(info);
 *     boolean cancelled = inflight.cancel(true);
 *     assertThat(cancelled).isFalse();
 *     assertThat(inflight.isCancelled()).isFalse();
 *     // future is still pending — underlying computation was not stopped
 *     assertThat(inflight.isDone()).isFalse();
 *   }
 * }
 * }</pre>
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
 * @see com.macstab.chaos.jvm.api.OperationType#ASYNC_CANCEL
 * @see com.macstab.chaos.jvm.api.ChaosSelector#async(java.util.Set)
 * @see ChaosAsyncCancelDelay
 */
@Repeatable(ChaosAsyncCancelSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.ASYNC,
    operationType = OperationType.ASYNC_CANCEL)
public @interface ChaosAsyncCancelSuppress {

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
   * @ChaosAsyncCancelSuppress(id = "primary",  probability = 0.001)
   * @ChaosAsyncCancelSuppress(id = "replica",  probability = 0.01)
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
    ChaosAsyncCancelSuppress[] value();
  }
}
