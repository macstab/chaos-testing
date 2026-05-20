/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.stressors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Stressor L1: force JIT to fill the code cache and fall back to interpreter. Unlike interceptor primitives, stressors don't intercept a JVM operation
 * — they spawn a self-driving routine that runs from activation to cleanup.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.CodeCachePressureTranslator")
public @interface ChaosCodeCachePressure {

  /** @return number of synthetic classes to JIT-compile (> 0) */
  int classCount() default 5000;

  /** @return methods per generated class (> 0) */
  int methodsPerClass() default 50;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the JVM agent is not active on the container */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
