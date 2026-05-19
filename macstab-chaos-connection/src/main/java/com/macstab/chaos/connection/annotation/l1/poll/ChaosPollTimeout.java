/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.poll;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * L1 chaos primitive: enforce a {@link #timeoutMs}-millisecond timeout on {@code poll} /
 * {@code epoll_wait} / {@code select} readiness-wait calls, gated by {@link #toxicity}.
 *
 * <p><strong>What this simulates:</strong> peer not signalling readiness — typical of stuck
 * connections, half-closed sockets, kernel scheduling stalls on the IO thread.
 *
 * <p><strong>Operation:</strong> implicitly {@code POLL} — only meaningful on the readiness-wait
 * family (libchaos-net rejects TIMEOUT on other operations).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.connection.model.NetRule#timeout
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionTimeoutTranslator")
public @interface ChaosPollTimeout {

  /** @return timeout to enforce on every match, in milliseconds (strictly positive) */
  long timeoutMs() default 5000L;

  /** @return per-call match probability, in {@code (0.0, 1.0]} */
  double toxicity() default 1.0;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-net */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
