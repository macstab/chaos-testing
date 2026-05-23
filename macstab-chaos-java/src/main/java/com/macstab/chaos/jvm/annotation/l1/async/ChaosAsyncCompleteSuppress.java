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
 * Makes {@link java.util.concurrent.CompletableFuture#complete CompletableFuture.complete(value)}
 * silently return {@code false} without transitioning the future to the completed state — every
 * thread blocking on that future hangs indefinitely.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code ASYNC_COMPLETE} operation with the {@code
 * suppress} effect. It intercepts the moment a {@code CompletableFuture} is about to receive a
 * normal (non-exceptional) result and discards the completion signal before it reaches the future.
 * The future is left permanently pending: {@code isDone()} returns {@code false}, no callbacks
 * fire, and any thread calling {@code get()} or {@code join()} blocks until it times out or the JVM
 * exits.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code CompletableFuture.complete}. When
 * the interceptor fires:
 *
 * <ol>
 *   <li>The call is intercepted before the future's internal state CAS executes.
 *   <li>The suppress effect discards the call — {@code complete} returns {@code false} as if the
 *       future had already been completed by another path.
 *   <li>The future remains in the {@code PENDING} state. No result is stored, no callbacks in the
 *       dependent stage chain are triggered, no waiting threads are unparked.
 * </ol>
 *
 * <p>This is distinct from {@code @ChaosAsyncCompleteExceptionalCompletion}, which replaces the
 * value with an exception (future completes, exceptionally). Here the future <em>never</em>
 * completes at all.
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <p>After applying this annotation, the following observations hold for any future that goes
 * through the suppressed {@code complete} path:
 *
 * <ul>
 *   <li>{@code cf.get(1, TimeUnit.SECONDS)} throws {@link java.util.concurrent.TimeoutException} —
 *       the future never becomes done.
 *   <li>{@code cf.isDone()} returns {@code false} — future is still pending.
 *   <li>{@code cf.isCompletedExceptionally()} returns {@code false} — no exception was injected.
 *   <li>{@code cf.isCancelled()} returns {@code false} — the future was not cancelled.
 *   <li>Callbacks registered via {@code thenApply}, {@code thenAccept}, {@code thenCompose} are
 *       never invoked.
 *   <li>Thread pools or reactive pipelines that depend on the future completion stall their worker
 *       threads until the thread's blocking {@code get()} call times out (if a timeout is set) or
 *       the thread is interrupted from the outside.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a computation finishes successfully
 * and the worker calls {@code complete(result)} — but the signal is lost before the waiting
 * consumers see it. This can happen when the completing thread dies between computing the value and
 * calling {@code complete}, when a message bus delivers the computation result to a dead partition,
 * or when a distributed coordinator silently drops the acknowledgement. In all cases the caller's
 * future hangs, coordinator timeouts accumulate, and upstream circuit breakers open.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets
 * {@code java.util.concurrent.CompletableFuture#complete(Object)} directly via a Byte Buddy method
 * interceptor installed during the premain phase. Because {@code CompletableFuture} is a JDK class,
 * the agent uses the bootstrap class loader's instrumentation channel (retransformation of already
 * loaded classes). If the JVM has already loaded {@code CompletableFuture} before the agent
 * attaches, the agent retransforms it in-place.
 *
 * <p><strong>CAS semantics and the false return.</strong> {@code CompletableFuture.complete} is
 * internally a compare-and-set on the result field (from {@code NIL} to the boxed value). The
 * suppress effect skips this CAS entirely — the method simply returns {@code false}. This is
 * indistinguishable from the case where a concurrent thread already completed the future, so
 * application code that inspects the return value of {@code complete} (idiomatic usage:
 * {@code if (!cf.complete(v)) handleRace()}) will silently enter the race-handling branch.
 *
 * <p><strong>Cascading effect on dependent stages.</strong> {@code CompletableFuture} stores its
 * completion stack as a linked list of {@code Completion} nodes. These nodes are processed only
 * when the future's result transitions from {@code NIL}. Because the transition never happens,
 * every dependent stage — {@code thenApply}, {@code thenCompose}, {@code whenComplete}, {@code
 * handle} — stays queued but never runs. If those stages are themselves awaited by further futures,
 * the stall propagates transitively through the entire pipeline.
 *
 * <p><strong>Virtual threads and structured concurrency.</strong> In Java 21+ code using
 * structured concurrency ({@code StructuredTaskScope}), a suppressed {@code complete} on the
 * scope's internal future can cause the scope's {@code join()} to block past the deadline set by
 * the scope's timeout, because the scope's completion latch is driven by the same
 * {@code CompletableFuture} machinery. Under Project Loom this ties up a virtual thread carrier
 * until interrupted — effectively a thread leak from the test's perspective.
 *
 * <p><strong>Scope and selector.</strong> The agent applies the suppression to <em>all</em>
 * {@code CompletableFuture.complete} calls originating from the container JVM, not just ones on a
 * particular future instance. If the target service uses multiple futures, all of them are
 * suppressed. To target a narrower scope, compose this annotation with a method-level scoping
 * annotation, or restrict the application window to a single {@code @Test} method (method-scope)
 * rather than the entire test class (class-scope).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosAsyncCompleteSuppress
 * class AsyncCompletionLostTest {
 *
 *   @Test
 *   void serviceTimesOutWhenCompletionIsLost(AppConnectionInfo info) throws Exception {
 *     CompletableFuture<String> result = client.fetchAsync(info);
 *     // the container-side complete() call is suppressed; result never arrives
 *     assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
 *         .isInstanceOf(TimeoutException.class);
 *     assertThat(result.isDone()).isFalse();
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Required:</strong>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#ASYNC_COMPLETE
 * @see com.macstab.chaos.jvm.api.ChaosSelector#async(java.util.Set)
 * @see ChaosAsyncCompleteExceptionalCompletion
 * @see ChaosAsyncCancelSuppress
 */
@Repeatable(ChaosAsyncCompleteSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.ASYNC,
    operationType = OperationType.ASYNC_COMPLETE)
public @interface ChaosAsyncCompleteSuppress {

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
   * @ChaosAsyncCompleteSuppress(id = "primary",  probability = 0.001)
   * @ChaosAsyncCompleteSuppress(id = "replica",  probability = 0.01)
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
    ChaosAsyncCompleteSuppress[] value();
  }
}
