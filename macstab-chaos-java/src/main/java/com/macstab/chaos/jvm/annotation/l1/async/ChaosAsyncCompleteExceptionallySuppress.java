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
 * Makes {@link java.util.concurrent.CompletableFuture#completeExceptionally
 * CompletableFuture.completeExceptionally(ex)} silently return {@code false} without delivering
 * the exception signal — the future remains permanently pending even though the producing code
 * believes it has reported a failure.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code ASYNC} selector family targeting the {@code
 * ASYNC_COMPLETE_EXCEPTIONALLY} operation with the {@code suppress} effect. It intercepts every
 * call to {@code CompletableFuture.completeExceptionally} in the container JVM and discards the
 * exception before the future's internal state machine sees it. The future stays in the {@code
 * PENDING} state: {@code isDone()} returns {@code false}, no exception callbacks fire, and any
 * thread blocked on {@code get()} or {@code join()} hangs indefinitely.
 *
 * <p>This is the exceptional-completion analogue of {@link ChaosAsyncCompleteSuppress}. Where that
 * annotation hides a successful result, this one hides a failure signal — leaving consumers
 * waiting for an error that never arrives.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code CompletableFuture.completeExceptionally}.
 * When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered before {@code completeExceptionally}'s CAS on the result field.
 *   <li>The suppress effect short-circuits the method: the exception is discarded and the CAS
 *       never runs.
 *   <li>{@code completeExceptionally} returns {@code false} to the caller, as if the future had
 *       already been completed by another thread.
 *   <li>The future remains in the {@code PENDING} state. No exception is stored, no callbacks
 *       fire, no waiting threads are unparked.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code cf.completeExceptionally(ex)} returns {@code false} — the exception signal is
 *       silently dropped.
 *   <li>{@code cf.isCompletedExceptionally()} returns {@code false}.
 *   <li>{@code cf.isDone()} returns {@code false} — the future is still pending.
 *   <li>{@code cf.get(1, TimeUnit.SECONDS)} throws {@link java.util.concurrent.TimeoutException}
 *       — the future never becomes done.
 *   <li>Error-handling stages registered via {@code exceptionally} and {@code handle} are never
 *       invoked; neither are normal {@code thenApply} stages.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a background error-reporter thread calls {@code
 * completeExceptionally} to propagate a database connection failure to waiting request handlers,
 * but the signal is lost — request handlers block indefinitely instead of failing fast, exhausting
 * the server's request thread pool while the database remains unavailable.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets
 * {@code java.util.concurrent.CompletableFuture#completeExceptionally(Throwable)} via Byte Buddy
 * retransformation of the bootstrap-loaded {@code CompletableFuture} class. The interceptor is
 * entered on the thread that called {@code completeExceptionally} — typically a worker or error
 * handler — before the method's internal CAS on the {@code result} field.
 *
 * <p><strong>AltResult wrapping.</strong> {@code CompletableFuture} stores exceptional results as
 * an {@code AltResult} wrapper around the throwable. By suppressing the call before the CAS, the
 * {@code AltResult} is never constructed and never written to the {@code result} field. The future
 * therefore presents as pending to all observers, including internal completion stack traversal.
 *
 * <p><strong>Cascading effects.</strong> Because the exceptional completion never fires, the entire
 * chain of dependent stages ({@code exceptionally}, {@code handle}, {@code whenComplete}) is never
 * triggered. Any downstream future that was awaiting this one's exceptional result remains pending
 * as well, potentially stalling an entire pipeline of error-recovery logic.
 *
 * <p><strong>Interaction with error-propagation patterns.</strong> Application code that uses
 * {@code completeExceptionally} as a signalling mechanism (e.g., a single error-future shared
 * across multiple stages) will find that the signal never propagates, making this annotation
 * effective at testing whether the application has backup mechanisms — watchdog timeouts,
 * heartbeat checks — to detect a stuck pipeline.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosAsyncCompleteSuppress} suppresses
 * the normal (non-exceptional) completion path. {@link ChaosAsyncCompleteExceptionalCompletion}
 * replaces a successful completion with an exception. This annotation suppresses the exceptional
 * completion path — turning explicit error reporting into a silent no-op.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosAsyncCompleteExceptionallySuppress
 * class ExceptionalSignalLostTest {
 *
 *   @Test
 *   void watchdogDetectsHungPipelineWhenErrorSignalDropped(AppConnectionInfo info) throws Exception {
 *     CompletableFuture<String> result = client.fetchAsync(info);
 *     // service-side completeExceptionally is suppressed; future stays pending
 *     assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
 *         .isInstanceOf(TimeoutException.class);
 *     assertThat(result.isCompletedExceptionally()).isFalse();
 *     assertThat(result.isDone()).isFalse();
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
 * @see com.macstab.chaos.jvm.api.OperationType#ASYNC_COMPLETE_EXCEPTIONALLY
 * @see com.macstab.chaos.jvm.api.ChaosSelector#async(java.util.Set)
 * @see ChaosAsyncCompleteSuppress
 * @see ChaosAsyncCompleteExceptionalCompletion
 */
@Repeatable(ChaosAsyncCompleteExceptionallySuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.ASYNC,
    operationType = OperationType.ASYNC_COMPLETE_EXCEPTIONALLY)
public @interface ChaosAsyncCompleteExceptionallySuppress {

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
   * @ChaosAsyncCompleteExceptionallySuppress(id = "primary",  probability = 0.001)
   * @ChaosAsyncCompleteExceptionallySuppress(id = "replica",  probability = 0.01)
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
    ChaosAsyncCompleteExceptionallySuppress[] value();
  }
}
