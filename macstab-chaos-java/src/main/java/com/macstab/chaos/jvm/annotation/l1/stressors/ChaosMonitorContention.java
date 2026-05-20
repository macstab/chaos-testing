/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.stressors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Stressor L1: N threads compete for a single lock with long hold times. Unlike interceptor primitives, stressors don't intercept a JVM operation
 * — they spawn a self-driving routine that runs from activation to cleanup.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.MonitorContentionTranslator")
public @interface ChaosMonitorContention {

  /** @return per-thread lock-hold duration in ms */
  long lockHoldMs() default 50L;

  /** @return number of contending threads (>= 2) */
  int contendingThreadCount() default 8;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the JVM agent is not active on the container */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
