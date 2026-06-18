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
 * Intercepts {@link java.util.concurrent.CompletableFuture#complete
 * CompletableFuture.complete(value)} and completes the future exceptionally instead — the caller's
 * value is discarded and every thread waiting on the future receives the configured exception
 * through {@code ExecutionException}.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code ASYNC} selector family targeting the {@code
 * ASYNC_COMPLETE} operation with the {@code exceptionalCompletion} effect. Unlike {@link
 * ChaosAsyncCompleteSuppress}, which leaves the future permanently pending, this annotation allows
 * the future to transition to the done state — but done exceptionally rather than with the intended
 * value. The exception type is selected via {@link #failureKind()} and the message via {@link
 * #message()}.
 *
 * <p>This is the primary primitive for simulating a computation that appears to finish but delivers
 * an error instead of the expected result — a common pattern in downstream service failures where
 * the response is received but indicates a fault.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code CompletableFuture.complete}. When
 * the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered before the future's normal CAS on its result field.
 *   <li>The exceptionalCompletion effect discards the incoming value and calls {@code
 *       completeExceptionally(new <FailureKind-exception>(message))} on the same future instance,
 *       completing it with the configured exception.
 *   <li>The original {@code complete(value)} body is skipped — only one completion signal is
 *       delivered to the future.
 *   <li>Waiting threads are unparked and receive the exception wrapped in {@code
 *       ExecutionException} when they call {@code get()} or {@code join()}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code cf.get()} throws {@link java.util.concurrent.ExecutionException} wrapping the
 *       configured exception — the original value is never visible.
 *   <li>{@code cf.isCompletedExceptionally()} returns {@code true}.
 *   <li>{@code cf.isDone()} returns {@code true} — the future is done, just not normally.
 *   <li>Callbacks registered via {@code thenApply} / {@code thenCompose} are never invoked; {@code
 *       exceptionally} and {@code handle} callbacks fire with the injected exception.
 *   <li>Reactive pipelines that propagate {@code onError} will receive the injected fault and route
 *       to their error-handling stages.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a downstream service returns an HTTP 200 response
 * but the payload indicates a processing error; the deserialization layer calls {@code complete}
 * with a domain error object rather than a valid result, but application error-handling code is
 * never tested for this path — this annotation injects that exact transition to verify the error
 * propagation chain.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code
 * java.util.concurrent.CompletableFuture#complete(Object)} via Byte Buddy retransformation of the
 * bootstrap-loaded {@code CompletableFuture} class. The interceptor runs on the same thread that
 * called {@code complete}, in the same call stack, before the future's result CAS.
 *
 * <p><strong>Exception type selection.</strong> {@link #failureKind()} maps to a specific {@code
 * Throwable} subclass: {@code RUNTIME} produces a {@code RuntimeException}, while other kinds
 * produce checked or specific error types as declared in {@link
 * com.macstab.chaos.jvm.api.ChaosEffect.FailureKind}. The exception is wrapped in {@code
 * ExecutionException} when retrieved via {@code get()} — callers that unwrap with {@code
 * getCause()} will see the raw injected exception.
 *
 * <p><strong>Ordering guarantee.</strong> Because the interceptor calls {@code
 * completeExceptionally} before the original {@code complete} body runs, the CAS in {@code
 * completeExceptionally} wins first. A subsequent {@code complete} from another thread racing at
 * exactly the same moment would find the future already done and return {@code false} — the
 * injected exception is always the winning completion.
 *
 * <p><strong>Cascading effects on dependent stages.</strong> {@code CompletableFuture}'s completion
 * stack is traversed immediately after the CAS. All dependent stages registered via {@code
 * thenApply}, {@code thenCompose}, and similar methods receive the exceptional result and propagate
 * it down the chain unless an {@code exceptionally} or {@code handle} stage absorbs it. A single
 * injected fault can therefore cascade through an entire async pipeline.
 *
 * <p><strong>Virtual threads.</strong> Under Project Loom, virtual threads blocked on {@code get()}
 * or {@code join()} are unmounted from their carrier as soon as they park; when the exceptional
 * completion arrives they are rescheduled. The delay between interception and rescheduling is
 * negligible, but the fault is always delivered.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosAsyncCompleteExceptionalCompletion(
 *     failureKind = ChaosEffect.FailureKind.RUNTIME,
 *     message = "injected by chaos: downstream failure")
 * class CompletionFaultedTest {
 *
 *   @Test
 *   void pipelineReceivesExceptionInsteadOfValue(AppConnectionInfo info) {
 *     CompletableFuture<String> future = client.fetchAsync(info);
 *     assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
 *         .isInstanceOf(ExecutionException.class)
 *         .hasCauseInstanceOf(RuntimeException.class)
 *         .hasMessageContaining("downstream failure");
 *     assertThat(future.isCompletedExceptionally()).isTrue();
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
 * @see com.macstab.chaos.jvm.api.OperationType#ASYNC_COMPLETE
 * @see com.macstab.chaos.jvm.api.ChaosSelector#async(java.util.Set)
 * @see ChaosAsyncCompleteSuppress
 * @see ChaosAsyncCompleteExceptionallySuppress
 */
@Repeatable(ChaosAsyncCompleteExceptionalCompletion.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionalCompletionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.ASYNC,
    operationType = OperationType.ASYNC_COMPLETE)
public @interface ChaosAsyncCompleteExceptionalCompletion {

  /**
   * @return failure kind for the exceptional completion
   */
  com.macstab.chaos.jvm.api.ChaosEffect.FailureKind failureKind() default
      com.macstab.chaos.jvm.api.ChaosEffect.FailureKind.RUNTIME;

  /**
   * @return exception message
   */
  String message() default "completed exceptionally by chaos L1";

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
   * @ChaosAsyncCompleteExceptionalCompletion(id = "primary",  probability = 0.001)
   * @ChaosAsyncCompleteExceptionalCompletion(id = "replica",  probability = 0.01)
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
    ChaosAsyncCompleteExceptionalCompletion[] value();
  }
}
