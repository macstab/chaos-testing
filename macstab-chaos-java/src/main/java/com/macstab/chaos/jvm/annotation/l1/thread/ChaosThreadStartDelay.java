/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.thread;

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
 * Parks the calling thread inside {@link Thread#start()} for the configured number of milliseconds
 * before the new thread enters the runnable state — every thread launch takes at least
 * {@code delayMs} longer than normal.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code THREAD} selector family with the {@code delay}
 * effect applied to the {@code THREAD_START} operation. It intercepts the moment the JVM is about
 * to transition a {@code Thread} object from the NEW state to the RUNNABLE state and artificially
 * inflates the latency of that transition. The annotation is declared on the test class or method
 * alongside a container annotation and is active for the lifetime of the annotated scope
 * (class-scope: {@code beforeAll} to {@code afterAll}; method-scope: {@code beforeEach} to
 * {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code Thread.start()}. When the
 * interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the native {@code start0()} call that hands the thread to the
 *       OS scheduler.
 *   <li>The delay effect calls {@code LockSupport.parkNanos} on the <em>calling</em> thread
 *       (the thread invoking {@code start()}) for the configured duration in milliseconds.
 *   <li>After the park returns, control falls through to the real {@code start0()} call and the new
 *       thread enters the runnable state normally.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Wall-clock time between calling {@code start()} and the new thread's first instruction is
 *       at least {@code delayMs} — assert with a {@code StopWatch} or latency histogram.
 *   <li>Thread pools that spawn on demand (e.g. {@code ThreadPoolExecutor} creating a worker for a
 *       new task) observe elevated task-submission latency; assert queue depth or response time.
 *   <li>The newly started thread itself runs normally once the delay elapses; assert that the
 *       thread's work eventually completes to distinguish this from a reject.
 *   <li>Timeout-guarded thread-creation paths (e.g. connection pool warm-up) may time out before
 *       {@code start()} returns; assert that a {@code TimeoutException} is raised, not a
 *       {@code NullPointerException} from a thread that never started.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a heavily loaded Linux kernel where
 * the {@code clone()} syscall stalls for tens of milliseconds because every CPU's run queue is
 * saturated, causing a connection-pool warm-up to time out and the application to serve requests
 * from a partially initialised pool — resulting in elevated error rates during startup.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent retransforms {@code java.lang.Thread} via the
 * bootstrap class loader's instrumentation channel (necessary because {@code Thread} is loaded
 * before any application class). Byte Buddy wraps the public {@code start()} method; the native
 * {@code start0()} method is not instrumented directly, keeping JNI bookkeeping intact.
 *
 * <p><strong>Who parks.</strong> The park applies to the <em>launching</em> thread, not the new
 * thread. This means thread-pool worker creation appears slow to the pool's management thread,
 * which may trigger pool-size checks or rejection policies if the pool's task queue fills during
 * the delay window.
 *
 * <p><strong>Distinction from {@code ChaosThreadStartReject}.</strong> The delay effect always
 * lets the thread start eventually. The reject effect throws before {@code start0()} executes, so
 * the thread never enters the runnable state. Use delay to test latency tolerance; use reject to
 * test error handling in the thread-creation path.
 *
 * <p><strong>Interaction with virtual threads.</strong> This annotation targets
 * {@code java.lang.Thread#start()} and therefore also fires for virtual threads created via
 * {@code Thread.ofVirtual().start(runnable)} on JDK 21+, because virtual thread launch goes
 * through the same {@code start()} method. Use {@code @ChaosVirtualThreadStartDelay} to target
 * only virtual threads without affecting platform threads.
 *
 * <p><strong>Cascading effects on thread pools.</strong> An {@code Executors.newCachedThreadPool()}
 * spawns a new thread per submitted task when all existing workers are busy. With a 200 ms start
 * delay, submitting 50 concurrent tasks causes the pool-management thread to park for 200 ms
 * between each new worker creation. If the submitter holds a lock during submission, that lock is
 * held for the entire park duration, blocking all other threads trying to acquire it.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosThreadStartDelay(delayMs = 500)
 * class SlowThreadStartTest {
 *
 *   @Test
 *   void poolWarmUpTimesOutUnderSlowThreadStart(AppConnectionInfo info) {
 *     Instant before = Instant.now();
 *     // trigger pool warm-up
 *     client.ping(info);
 *     assertThat(Duration.between(before, Instant.now())).isGreaterThanOrEqualTo(Duration.ofMillis(500));
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
 * @see com.macstab.chaos.jvm.api.OperationType#THREAD_START
 * @see com.macstab.chaos.jvm.api.ChaosSelector#thread(java.util.Set)
 * @see ChaosThreadStartReject
 * @see ChaosVirtualThreadStartDelay
 */
@Repeatable(ChaosThreadStartDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.THREAD,
    operationType = OperationType.THREAD_START)
public @interface ChaosThreadStartDelay {

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
   * @ChaosThreadStartDelay(id = "primary",  probability = 0.001)
   * @ChaosThreadStartDelay(id = "replica",  probability = 0.01)
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
    ChaosThreadStartDelay[] value();
  }
}
