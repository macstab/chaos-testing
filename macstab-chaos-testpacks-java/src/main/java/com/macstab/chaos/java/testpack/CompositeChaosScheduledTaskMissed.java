/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Suppresses {@code ScheduledExecutorService} task execution at the tick point so that tasks are
 * dequeued from the scheduler but their body is never run, simulating the effect of a scheduled
 * task that is silently dropped — for example, a heartbeat, cache-eviction, or lease-renewal task.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects a {@code suppress} effect on the {@code SCHEDULE_TICK} operation via the JVM chaos
 * agent, using probability {@link #probability()} to control how often ticks are suppressed. In
 * production, missed scheduled tasks occur when thread pools are undersized, task queues overflow,
 * or the underlying executor is shut down without draining.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Critical scheduled tasks (lease renewal, cache eviction, metric flush) fail silently when their
 * tick is suppressed. The application may not detect the miss until a downstream system observes
 * the missing action (expired lease, full cache, stale metrics).
 *
 * <h2>Industry references</h2>
 *
 * <p>Apache Kafka's controller relies on heartbeat tasks; a missed heartbeat causes leadership
 * re-election. Consul's session TTL is maintained by periodic renewal tasks — a missed renewal
 * causes the session (and any associated locks) to expire.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosScheduledTaskMissed(probability = 0.5)
 * class ScheduledTaskMissedTest {
 *   @Test
 *   void leaseRenewalExpiredWhenTaskMissed(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosScheduledTaskMissed.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.ScheduledTaskMissedComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosScheduledTaskMissed {

  /**
   * Probability in {@code (0.0, 1.0]} that a scheduled task tick is suppressed.
   *
   * @return probability; default 0.5
   */
  double probability() default 0.5;

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosScheduledTaskMissed[] value();
  }
}
