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
 * Parks the calling thread for {@link #delayMs} to {@link #maxDelayMs} milliseconds before entering
 * every {@link java.util.concurrent.ExecutorService#awaitTermination awaitTermination(timeout,
 * unit)} call, making the total time the caller blocks waiting for executor drain longer than its
 * configured timeout budget.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive in the {@code EXECUTOR} selector family targeting the {@code
 * EXECUTOR_AWAIT_TERMINATION} operation with the {@code delay} effect. It intercepts every call to
 * {@code awaitTermination} on {@code ExecutorService} implementations in the container JVM and
 * parks the calling thread for the configured duration before the method's real blocking logic
 * begins. After the sleep, {@code awaitTermination} proceeds normally: it blocks until the executor
 * terminates or the caller-specified timeout elapses.
 *
 * <p>The net effect is that the caller's timeout budget is consumed by the injected sleep before
 * the actual termination wait starts — causing the caller's timeout to expire earlier than expected
 * relative to the shutdown initiation time.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code ExecutorService.awaitTermination}.
 * When the interceptor fires:
 *
 * <ol>
 *   <li>The interceptor is entered on the calling thread before the blocking wait begins.
 *   <li>The delay effect calls {@code Thread.sleep(delayMs)} (or a random value in {@code [delayMs,
 *       maxDelayMs]}), parking the calling thread.
 *   <li>After the sleep, the original {@code awaitTermination(timeout, unit)} body executes. It
 *       waits up to the full specified timeout for the executor to terminate. If the executor
 *       terminated during the sleep, {@code awaitTermination} returns {@code true} immediately.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The total wall-clock time from calling {@code awaitTermination} to its return is at least
 *       {@link #delayMs} ms longer than the specified timeout (or the actual drain time if
 *       shorter).
 *   <li>If the caller uses a fixed timeout (e.g., {@code awaitTermination(5, SECONDS)}) and the
 *       executor takes 4 seconds to drain, adding a 2-second delay causes the method to return
 *       {@code false} — the executor did not terminate within the budget because the delay consumed
 *       2 seconds of it before waiting began.
 *   <li>Shutdown sequences that call {@code shutdown()} followed by {@code awaitTermination} will
 *       observe the termination check firing later than expected.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a Kubernetes prestop hook calls {@code
 * awaitTermination(30, SECONDS)} to drain in-flight requests before pod termination; a slow
 * deregistration step (lock contention, DNS TTL) consumes part of that budget before the method
 * even enters its wait loop — causing the pod to be killed by the orchestrator before requests are
 * drained.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent targets {@code
 * java.util.concurrent.ExecutorService#awaitTermination(long, TimeUnit)} on all concrete
 * implementations via Byte Buddy retransformation. The sleep fires before the method's internal
 * {@code mainLock.wait(nanos)} call in {@code ThreadPoolExecutor}.
 *
 * <p><strong>Timeout budget erosion.</strong> The caller passes a timeout to {@code
 * awaitTermination}; that timeout is relative to when the method starts its blocking wait. Because
 * the delay fires before the wait starts, the injected sleep is added on top of the caller's
 * timeout — the caller blocks for up to {@code sleep + timeout} ms in total. If the executor drains
 * during the sleep (because it was already nearly done), the method returns {@code true}
 * immediately after the sleep, and the full timeout is not consumed.
 *
 * <p><strong>Interaction with virtual threads.</strong> If the calling thread is a virtual thread
 * (Java 21+), the sleep unmounts it from its carrier. The virtual thread remains frozen during the
 * sleep and resumes when the sleep expires, at which point the real {@code awaitTermination} wait
 * begins — also potentially unmounting the virtual thread if the executor has not yet terminated.
 *
 * <p><strong>Distinguishing from siblings.</strong> {@link ChaosExecutorAwaitTerminationGate}
 * blocks the calling thread until explicitly released by the test, enabling precise timing control.
 * This annotation applies a fixed random sleep and then lets the wait proceed with its natural
 * timeout — useful for simulating latency without needing external release coordination.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosExecutorAwaitTerminationDelay(delayMs = 3000)
 * class AwaitTerminationBudgetTest {
 *
 *   @Test
 *   void shutdownTimesOutWhenAwaitTerminationDelayExceedsBudget(AppConnectionInfo info)
 *       throws Exception {
 *     // app calls awaitTermination(2, SECONDS) internally; 3s delay > 2s budget
 *     boolean terminated = client.gracefulShutdown(info);
 *     assertThat(terminated).isFalse(); // awaitTermination returned false
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
 * @see com.macstab.chaos.jvm.api.OperationType#EXECUTOR_AWAIT_TERMINATION
 * @see com.macstab.chaos.jvm.api.ChaosSelector#executor(java.util.Set)
 * @see ChaosExecutorAwaitTerminationGate
 * @see ChaosExecutorShutdownDelay
 */
@Repeatable(ChaosExecutorAwaitTerminationDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.EXECUTOR,
    operationType = OperationType.EXECUTOR_AWAIT_TERMINATION)
public @interface ChaosExecutorAwaitTerminationDelay {

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
   * @ChaosExecutorAwaitTerminationDelay(id = "primary",  probability = 0.001)
   * @ChaosExecutorAwaitTerminationDelay(id = "replica",  probability = 0.01)
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
    ChaosExecutorAwaitTerminationDelay[] value();
  }
}
