/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.testpack;

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
 * <p>Simulates a CPU-starved or scheduler-overloaded system where every {@code nanosleep()} call
 * returns {@link #latencyMs} milliseconds later than requested — modelling the "timer cascade"
 * failure mode where a wave of simultaneously-firing timers overwhelms the kernel scheduler and
 * causes all subsequent timer firings to be late.
 *
 * <h2>How it is created</h2>
 *
 * <p>Applies one libchaos-time rule: {@code nanosleep:LATENCY:<latencyMs>}. The latency is added on
 * top of the requested sleep duration: if the application calls {@code nanosleep({tv_sec=0,
 * tv_nsec=10_000_000})} (10 ms) it will actually sleep for {@code 10 + latencyMs} ms before the
 * call returns. This simulates a scheduler that is behind on timer delivery — the application's
 * timing budget is exceeded without the application receiving an error.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Late timer firings cause cascading deadline misses in systems with chained timeouts. A retry
 * scheduler that sleeps between attempts fires its next retry late; if its outer timeout also uses
 * wall time, the outer timeout may expire before the retry fires, causing the entire operation to
 * fail even though the retry would have succeeded. Heartbeat-based failure detectors using {@code
 * nanosleep} for their send interval declare peers as dead prematurely. Circuit breakers that use
 * sleep-based half-open timers open permanently under this scenario.
 *
 * <h2>Industry references</h2>
 *
 * <p>Timer cascades are documented in the Linux kernel mailing list archives (LKML, hrtimer
 * thundering herd, 2007) and in the "Prometheus: Monitoring at Scale" chapter on "thundering herd
 * scrape storms" where simultaneous timer expirations cause CPU spikes. The AWS EC2 bare-metal SRE
 * team has published guidance on timer slack ({@code TIMER_SLACK_NS}) as a mitigation for exactly
 * this failure mode. Java's {@code ScheduledExecutorService} is susceptible when its underlying
 * {@code Thread.sleep} is backed by {@code nanosleep} and the system is overloaded.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @KafkaCluster
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @CompositeChaosTimerCascade(latencyMs = 200)
 * class TimerCascadeTest {
 *
 *   @Test
 *   void retrySchedulerRespectsTotalDeadlineUnderSlowTimers() {
 *     // assert that the retry scheduler does not overshoot its outer timeout budget
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosTimerCascade.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.time.testpack.composers.TimerCascadeComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosTimerCascade {

  /**
   * Extra latency added to every {@code nanosleep()} call on top of the requested sleep duration.
   * Defaults to 100 ms — enough to cause deadline misses in typical 500 ms timeout budgets while
   * keeping the container responsive enough to observe the cascading effects.
   *
   * @return additional latency in milliseconds
   */
  long latencyMs() default 100L;

  /**
   * Container id to target. Empty string (the default) applies the scenario to every container
   * prepared with libchaos-time.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosTimerCascade[] value();
  }
}
