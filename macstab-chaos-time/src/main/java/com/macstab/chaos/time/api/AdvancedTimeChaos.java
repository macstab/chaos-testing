/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.api;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.TimeChaos;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;

/**
 * Capability-tier interface exposing libchaos-time's time-syscall fault-injection surface —
 * including the unique {@code OFFSET} effect for per-clock skew without globally shifting wall
 * time.
 *
 * <p><strong>Pre-flight contract.</strong> Every method on this interface requires that the target
 * container has been prepared with libchaos-time <em>before</em> {@code container.start()} — the
 * {@code .so} is hooked via {@code LD_PRELOAD}, which the dynamic loader only honours at process
 * launch. Skipping preparation raises {@link LibchaosNotPreparedException} loudly. Annotate the
 * test class with {@code @SyscallLevelChaos(LibchaosLib.TIME)} to let {@code
 * ChaosTestingExtension} drive preparation.
 *
 * <p><strong>Capability uplift over {@link TimeChaos}.</strong> The portable parent interface
 * ({@code shift} / {@code drift}) models whole-container wall-clock manipulation via libfaketime.
 * This interface adds per-syscall failure injection and per-clock-id skew that libfaketime cannot
 * reach:
 *
 * <ul>
 *   <li><strong>Clock-read failure</strong> — make {@code clock_gettime()} return {@code EINVAL},
 *       {@code EFAULT}, {@code EPERM}, etc.
 *   <li><strong>Per-clock skew</strong> — {@code OFFSET:-1500} on {@code
 *       clock_gettime/monotonic} skews only the monotonic clock by -1.5s without touching
 *       wall-clock time — surfaces latent assumptions that wall-clock and monotonic move in
 *       lockstep.
 *   <li><strong>Sleep failure</strong> — {@code nanosleep()} / {@code usleep()} return {@code
 *       EINTR}: exercises the application's interrupted-sleep retry loop, bypassing
 *       SA_RESTART-style autorestart.
 *   <li><strong>Sleep latency</strong> — make every {@code nanosleep()} sleep an extra N ms —
 *       models a slow scheduler / cgroup CPU starvation without configuring the actual cgroup.
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * class MyTest {
 *   @Test
 *   void surfaceWallVsMonotonicSkew(TimeChaos chaos, GenericContainer<?> app) {
 *     AdvancedTimeChaos adv = (AdvancedTimeChaos) chaos;
 *
 *     // Only the monotonic clock jumps backward 1.5s — wall-clock is untouched.
 *     RuleHandle h = adv.skewClock(app, TimeClock.MONOTONIC, Duration.ofMillis(-1500));
 *
 *     driveLoadUntilFailureSurfaces(app);
 *
 *     adv.remove(app, h);
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface AdvancedTimeChaos extends TimeChaos {

  // ==================== Generic rule API ====================

  /**
   * Apply a single libchaos-time rule.
   *
   * @return handle for later removal
   * @throws NullPointerException if any argument is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-time is not active on {@code container}
   */
  RuleHandle apply(GenericContainer<?> container, TimeRule rule);

  /**
   * Apply a batch of rules in a single round-trip. Validates every rule before committing any of
   * them (fail-fast).
   */
  List<RuleHandle> applyAll(GenericContainer<?> container, List<TimeRule> rules);

  /** Surgically remove a single previously-applied rule. Idempotent. */
  void remove(GenericContainer<?> container, RuleHandle handle);

  /** Remove every rule this strategy has applied to {@code container}. */
  void removeAll(GenericContainer<?> container);

  // ==================== Raw-rule escape hatches ====================

  /** Apply an errno fault to an arbitrary selector — escape hatch. */
  RuleHandle errno(
      GenericContainer<?> container,
      TimeSelector selector,
      TimeErrno errno,
      double probability);

  /** Apply a latency effect to an arbitrary selector. */
  RuleHandle latency(GenericContainer<?> container, TimeSelector selector, Duration delay);

  /**
   * Apply an {@code OFFSET} effect to a specific {@link TimeClock} under {@code clock_gettime} —
   * the canonical per-clock skew verb.
   *
   * @throws IllegalArgumentException if {@code clock} is {@code null}
   */
  RuleHandle offset(GenericContainer<?> container, TimeClock clock, Duration delta);

  /** Apply an {@code OFFSET} effect to a specific {@link TimeClock}, gated by probability. */
  RuleHandle offset(
      GenericContainer<?> container, TimeClock clock, Duration delta, double probability);

  // ==================== CLOCK_GETTIME ====================

  /** Inject {@code EINVAL} on {@code clock_gettime()} at the given probability. */
  RuleHandle failClockGet(GenericContainer<?> container, double probability);

  /**
   * Inject an arbitrary errno on {@code clock_gettime()} — gives access to {@code EFAULT}
   * (bad pointer), {@code EPERM} (clock not permitted), {@code ENOSYS} (kernel missing the call).
   */
  RuleHandle failClockGetWithErrno(
      GenericContainer<?> container, TimeErrno errno, double probability);

  /**
   * Delay every {@code clock_gettime()} by {@code delay} — simulate a slow vDSO / TSC fallback
   * path.
   */
  RuleHandle slowClockGet(GenericContainer<?> container, Duration delay);

  /**
   * Skew a specific clock by {@code delta} (signed) — adds {@code delta} to every {@code struct
   * timespec} returned by {@code clock_gettime(clock, ...)}. Other clocks are untouched. Use a
   * negative delta to shift the clock into the past.
   */
  RuleHandle skewClock(GenericContainer<?> container, TimeClock clock, Duration delta);

  // ==================== NANOSLEEP ====================

  /** Inject {@code EINTR} on {@code nanosleep()} at the given probability. */
  RuleHandle failNanosleep(GenericContainer<?> container, double probability);

  /** Delay every {@code nanosleep()} by an additional {@code delay} before the real call fires. */
  RuleHandle slowNanosleep(GenericContainer<?> container, Duration delay);

  /**
   * Inject {@code EINTR} on {@code nanosleep()} — semantic alias of {@link #failNanosleep}. Models
   * a signal interrupting the sleep regardless of SA_RESTART.
   */
  RuleHandle signalInterruptSleep(GenericContainer<?> container, double probability);

  // ==================== USLEEP ====================

  /** Inject {@code EINTR} on {@code usleep()} at the given probability. */
  RuleHandle failUsleep(GenericContainer<?> container, double probability);

  /** Delay every {@code usleep()} by an additional {@code delay} before the real call fires. */
  RuleHandle slowUsleep(GenericContainer<?> container, Duration delay);

  /**
   * Inject {@code EINTR} on {@code usleep()} — semantic alias of {@link #failUsleep}. Models a
   * signal interrupting the microsecond sleep.
   */
  RuleHandle signalInterruptMicrosleep(GenericContainer<?> container, double probability);
}
