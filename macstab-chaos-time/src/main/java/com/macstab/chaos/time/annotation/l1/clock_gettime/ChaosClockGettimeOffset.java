/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.clock_gettime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * L1 chaos primitive: shift the {@code struct timespec} returned by {@code clock_gettime} by
 * {@link #deltaMs} milliseconds. Offset is libchaos-time's unique third effect kind — valid
 * only on {@code CLOCK_GETTIME} (other time syscalls don't return a timespec to mutate).
 *
 * <p><strong>What this simulates:</strong> NTP step events, hypervisor clock-drift correction,
 * scheduler clock skew on resumed-from-suspend hosts, deliberate-clock-rewind attacks against
 * cache TTLs and timestamp-based authentication tokens.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeOffset(deltaMs = -60_000L)  // rewind clock by 1 minute
 * class MyTest { ... }
 * }</pre>
 *
 * <p><strong>Sign convention:</strong> negative deltas rewind the clock (older timestamps),
 * positive deltas advance it. The shift applies to every {@code CLOCK_REALTIME} /
 * {@code CLOCK_MONOTONIC} / etc. read uniformly — for per-clock targeting use the imperative
 * API ({@code AdvancedTimeChaos.applyClockGettimeOffset(container, clock, delta)}).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.time.model.TimeRule#offset(java.time.Duration, double)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeOffsetTranslator")
public @interface ChaosClockGettimeOffset {

  /** @return clock shift in milliseconds; negative = rewind, positive = advance */
  long deltaMs() default -60_000L;

  /** @return probability the offset fires when matched, in {@code (0.0, 1.0]} */
  double probability() default 1.0;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-time */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
