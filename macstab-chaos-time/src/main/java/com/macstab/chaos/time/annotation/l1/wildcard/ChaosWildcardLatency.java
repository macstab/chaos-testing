/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.wildcard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.time.annotation.l1.TimeLatencyBinding;
import com.macstab.chaos.time.model.TimeSelector;

/**
 * L1 chaos primitive: delay every libchaos-time-intercepted {@code every interposed time syscall} call by
 * {@link #delayMs} milliseconds.
 *
 * <p><strong>What this simulates:</strong> kernel-side clock-subsystem stalls (rare but real
 * — vDSO fallback path, REALTIME_COARSE staleness window, kernel-config-dependent timer
 * resolution mismatches).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.time.model.TimeRule#latency(TimeSelector, java.time.Duration)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeLatencyTranslator")
@TimeLatencyBinding(selector = TimeSelector.WILDCARD)
public @interface ChaosWildcardLatency {

  /** @return latency to apply on every match, in milliseconds (non-negative) */
  long delayMs() default 10L;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-time */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
