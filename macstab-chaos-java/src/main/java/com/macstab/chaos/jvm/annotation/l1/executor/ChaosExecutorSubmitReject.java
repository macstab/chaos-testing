/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.executor;

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
 * Makes every {@link java.util.concurrent.ExecutorService#submit ExecutorService.submit} and {@link
 * java.util.concurrent.ForkJoinPool#submit ForkJoinPool.submit} call throw {@link
 * java.util.concurrent.RejectedExecutionException} before the task enters the queue — the task is
 * never scheduled and the caller's submission attempt fails immediately.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * EXECUTOR_SUBMIT} operation with the {@code reject} effect. It intercepts every task submission in
 * the container JVM and throws {@code RejectedExecutionException} with the configured {@link
 * #message()} before the task is placed on any work queue. No task object is enqueued, no {@code
 * Future} is returned, and no worker thread is notified.
 *
 * <p>This directly replicates the exception thrown by {@code ThreadPoolExecutor}'s {@code
 * AbortPolicy} when the queue is full and all threads are busy, or when the executor has been shut
 * down — the most common production failure mode for executor saturation.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on the {@code submit} methods of {@code
 * ExecutorService} and {@code ForkJoinPool}. When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the submitting thread before the task touches the queue.
 *   <li>The reject effect throws {@code new RejectedExecutionException(message)} immediately.
 *   <li>The original {@code submit} body never runs; the task object is discarded.
 *   <li>The exception propagates up the call stack of the submitting thread.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code executor.submit(task)} throws {@link
 *       java.util.concurrent.RejectedExecutionException} — every call, every time the rule is
 *       active.
 *   <li>No {@code Future} is returned; no task runs.
 *   <li>Application code that catches {@code RejectedExecutionException} and applies a retry or
 *       fallback will exercise that path on every submission.
 *   <li>Application code that does not catch {@code RejectedExecutionException} will propagate an
 *       unhandled exception up through the calling layer, potentially aborting a request or
 *       crashing a background thread.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a bounded {@code ThreadPoolExecutor} configured with
 * {@code AbortPolicy} becomes saturated under load — all threads are busy and the queue is full;
 * any new submission receives {@code RejectedExecutionException}; services that do not implement
 * back-pressure or retry logic drop tasks silently or crash request handlers.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets the {@code submit(Callable)}, {@code
 * submit(Runnable)}, and {@code submit(Runnable, T)} overloads on {@code
 * java.util.concurrent.ExecutorService} and the equivalent on {@code
 * java.util.concurrent.ForkJoinPool} via Byte Buddy retransformation of bootstrap-loaded JDK
 * classes. The exception is thrown before any internal state in the executor is modified.
 *
 * <p><strong>Exception identity.</strong> The thrown exception is a plain {@code
 * RejectedExecutionException} — the same type that {@code ThreadPoolExecutor}'s {@code AbortPolicy}
 * produces. Application code that catches this specific type will behave identically to the
 * production saturation scenario. The {@link #message()} attribute allows the test to embed a
 * marker string to distinguish injected rejections from real ones in logs.
 *
 * <p><strong>Interaction with {@code execute} vs {@code submit}.</strong> This annotation targets
 * {@code submit} only. If the application uses {@code executor.execute(runnable)} directly, that
 * path is covered by a different interceptor binding. Use {@code ChaosExecutorSubmitReject}
 * together with an appropriate {@code execute}-level annotation if both paths need coverage.
 *
 * <p><strong>Cascading effects.</strong> Any {@code Future} chained on the expected submission
 * result will never receive a value or exception from the rejected task — it stays pending unless
 * the application has defensive null checks or timeouts after catching the rejection. Reactive
 * frameworks that use executor-backed schedulers may log the rejection and schedule a retry, or may
 * drop the work item entirely depending on their error strategy.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorSubmitDelay} allows the
 * task to be submitted, just slowly. {@link ChaosExecutorSubmitGate} holds the submitting thread
 * until the test releases it. This annotation is the only one that prevents the task from ever
 * reaching the queue.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosExecutorSubmitReject(message = "chaos: executor saturated")
 * class RejectedSubmitTest {
 *
 *   @Test
 *   void serviceAppliesFallbackOnRejection(AppConnectionInfo info) {
 *     // the application should catch RejectedExecutionException and return a default response
 *     String response = client.fetchWithFallback(info);
 *     assertThat(response).isEqualTo("default");
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
 * @see com.macstab.chaos.jvm.api.OperationType#EXECUTOR_SUBMIT
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosExecutorSubmitDelay
 * @see ChaosExecutorSubmitGate
 */
@Repeatable(ChaosExecutorSubmitReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.EXECUTOR_SUBMIT)
public @interface ChaosExecutorSubmitReject {

  /**
   * @return exception message used by the reject effect
   */
  String message() default "rejected by chaos L1";

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
   * @ChaosExecutorSubmitReject(id = "primary",  probability = 0.001)
   * @ChaosExecutorSubmitReject(id = "replica",  probability = 0.01)
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
    ChaosExecutorSubmitReject[] value();
  }
}
