/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.nanosleep;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.time.annotation.l1.TimeErrnoBinding;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeSelector;

/**
 * L1 chaos primitive: inject {@code EPERM} on every libchaos-time-intercepted
 * {@code nanosleep} call, gated by {@link #probability}.
 *
 * <p><strong>What this simulates:</strong> operation not permitted — typical of capability-dropped containers.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.time.model.TimeRule#errno(TimeSelector, TimeErrno, double)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.NANOSLEEP, errno = TimeErrno.EPERM)
public @interface ChaosNanosleepEperm {

  /** @return probability the errno fires when matched, in {@code (0.0, 1.0]} */
  double probability() default 1.0;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-time */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
