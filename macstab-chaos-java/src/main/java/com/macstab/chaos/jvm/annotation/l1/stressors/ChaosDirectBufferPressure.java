/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.stressors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Stressor L1: exhaust direct buffer memory (off-heap NIO). Unlike interceptor primitives, stressors don't intercept a JVM operation
 * — they spawn a self-driving routine that runs from activation to cleanup.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DirectBufferPressureTranslator")
public @interface ChaosDirectBufferPressure {

  /** @return total bytes to allocate off-heap (> 0) */
  long totalBytes() default 268_435_456L;

  /** @return per-buffer size in bytes (> 0) */
  int bufferSizeBytes() default 1_048_576;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the JVM agent is not active on the container */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
