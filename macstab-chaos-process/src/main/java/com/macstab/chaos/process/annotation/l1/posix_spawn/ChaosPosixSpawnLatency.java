/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawn;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessLatencyBinding;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * L1 chaos primitive: delay every libchaos-process-intercepted {@code posix_spawn} call by
 * {@link #delayMs} milliseconds before delegating to libc.
 *
 * <p><strong>What this simulates:</strong> the process-syscall latency increase that surfaces
 * under cgroup contention, scheduler pressure, and slow filesystem lookups during execve PATH
 * resolution — none of which fail with an errno but all of which stress timeouts in the
 * application.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.process.model.ProcessRule#latency(ProcessSelector, java.time.Duration)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessLatencyTranslator")
@ProcessLatencyBinding(selector = ProcessSelector.POSIX_SPAWN)
public @interface ChaosPosixSpawnLatency {

  /** @return latency to apply on every match, in milliseconds (non-negative) */
  long delayMs() default 100L;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-process */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
