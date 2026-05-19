/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.rename_from;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * L1 chaos primitive: delay every libchaos-io-intercepted {@code rename_from} call by
 * {@link #delayMs} milliseconds.
 *
 * <p><strong>What this simulates:</strong> filesystem-level I/O latency (disk contention,
 * journal-replay storms, network-filesystem RTT spikes).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.filesystem.model.IoRule#latency
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.RENAME_FROM)
public @interface ChaosRenameFromLatency {

  /** @return latency to apply on every match, in milliseconds (non-negative) */
  long delayMs() default 50L;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-io */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
