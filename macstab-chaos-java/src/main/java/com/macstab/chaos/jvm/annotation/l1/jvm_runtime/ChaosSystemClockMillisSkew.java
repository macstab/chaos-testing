/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.jvm_runtime;

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
 * Shifts the value returned by {@code System.currentTimeMillis()} by a fixed, drifting, or frozen
 * offset, simulating wall-clock skew between distributed nodes.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code SYSTEM_CLOCK_MILLIS} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code System.currentTimeMillis()} in the target
 *       container's JVM.
 *   <li>The interceptor adds {@link #skewMs()} milliseconds to the raw value before returning it to
 *       the caller; a negative offset moves the clock into the past.
 *   <li>In {@code DRIFT} mode the offset grows linearly over time, simulating a clock that is
 *       running fast or slow relative to NTP; in {@code FREEZE} mode the returned value is pinned
 *       at the moment the rule was activated.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Token / session expiry fires early or late.</strong> Assert that the application
 *       rejects or re-negotiates tokens whose expiry is evaluated against the skewed clock.
 *   <li><strong>Distributed consensus failures.</strong> Raft leader-election timeouts and
 *       Zookeeper session expirations depend on wall-clock comparisons across nodes; with one node
 *       skewed, assert that the cluster detects the anomaly and does not split-brain.
 *   <li><strong>Scheduled-job misfires.</strong> Cron-driven and delay-queue tasks keyed to {@code
 *       currentTimeMillis} may fire at the wrong time; assert idempotency and correct catch-up
 *       behaviour.
 *   <li><strong>Production failure mode:</strong> unbounded clock skew causes Kafka consumer group
 *       rebalances (broker uses wall time for heartbeat deadlines), JWT verification failures, and
 *       mTLS certificate-validity rejections.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code System.currentTimeMillis()} is a {@code native} method declared in {@code
 * java.lang.System}. Intercepting it requires a Byte Buddy native-method delegation stub: the agent
 * introduces a Java-visible wrapper that the JVM calls instead of the raw native, allowing the
 * interceptor to add the skew before returning. This differs from plain bytecode advice and
 * requires the agent to be loaded at JVM startup via {@code -javaagent} so that the bootstrap class
 * loader can see the stub.
 *
 * <p>Wall-clock skew is qualitatively different from monotonic-clock skew (see {@link
 * ChaosSystemClockNanosSkew}). {@code currentTimeMillis()} reflects the system wall clock, which is
 * used for timestamps, token expiry, and calendar scheduling. Its value can jump forward or
 * backward (NTP slew), making it inherently unreliable for measuring elapsed time. Distributed
 * protocols that use wall time for leader leases or session expiry are therefore susceptible to
 * divergence when nodes disagree on the current time by more than their configured tolerance.
 *
 * <p>In Raft-based systems, the leader periodically renews its lease by comparing its local {@code
 * currentTimeMillis()} against the lease duration. If the leader's clock is skewed backward by more
 * than the lease duration it believes the lease has not expired when followers have already timed
 * it out, resulting in a window with two nodes that believe they are leader. Zookeeper session
 * expiry works similarly: the server subtracts the client's last-heartbeat wall time from the
 * current wall time; a session from a node whose clock is in the future appears to have sent its
 * last heartbeat very recently and may never expire.
 *
 * <p>The {@code DRIFT} mode increases the offset at a configurable rate, allowing tests to observe
 * how the system reacts to gradual skew growth — the kind caused by a VM whose NTP daemon has
 * stopped or whose hypervisor clock is running slow. The {@code FREEZE} mode pins the clock at the
 * activation instant, useful for testing time-based caches that are expected to refresh when the
 * clock stops advancing.
 *
 * <p>Because the skew is applied inside the container JVM only, the test-side JVM retains the
 * correct wall clock, making it straightforward to measure how long the container took to detect
 * and recover from the anomaly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSystemClockMillisSkew(skewMs = -120_000, mode = ClockSkewMode.DRIFT)
 * class ClockSkewTest {
 *   @Test
 *   void sessionExpiryHandledCorrectlyUnderSkew(ConnectionInfo info) { ... }
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
@Repeatable(ChaosSystemClockMillisSkew.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ClockSkewTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.SYSTEM_CLOCK_MILLIS)
public @interface ChaosSystemClockMillisSkew {

  /**
   * @return clock offset in milliseconds; positive = future, negative = past; non-zero
   */
  long skewMs() default -60_000L;

  /**
   * @return how the skew evolves over time (FIXED / DRIFT / FREEZE)
   */
  com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewMode mode() default
      com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewMode.FIXED;

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
   * @ChaosSystemClockMillisSkew(id = "primary",  probability = 0.001)
   * @ChaosSystemClockMillisSkew(id = "replica",  probability = 0.01)
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
    ChaosSystemClockMillisSkew[] value();
  }
}
