/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.poll;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * L1 chaos primitive: delay every libchaos-net-intercepted {@code poll} call by
 * {@link #delayMs} milliseconds.
 *
 * <p><strong>What this simulates:</strong> per-syscall latency increase (typical of network
 * congestion, load-balancer queueing, kernel TCP retransmits).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.connection.model.NetRule#latency
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.POLL)
public @interface ChaosPollLatency {

  /** @return latency to apply on every match, in milliseconds (non-negative) */
  long delayMs() default 100L;

  /** @return probability the latency fires when matched, in {@code (0.0, 1.0]} */
  double toxicity() default 1.0;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-net */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
