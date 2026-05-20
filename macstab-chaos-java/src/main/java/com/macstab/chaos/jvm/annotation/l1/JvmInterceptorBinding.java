/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.jvm.api.OperationType;

/**
 * Meta-annotation declaring the (selector kind, operation type) tuple encoded by a JVM
 * interceptor L1 annotation. Read reflectively by the per-effect translators to build the typed
 * {@link com.macstab.chaos.jvm.api.ChaosSelector}.
 *
 * <p>The effect kind is implicit — it's determined by which translator the L1 annotation's
 * {@code @ChaosL1(translator = "...")} meta-annotation names, so each effect family
 * (Delay / Reject / Suppress / ExceptionInjection / etc.) has its own translator class that
 * reads this binding and its effect-specific attributes ({@code delayMs} for Delay, {@code
 * message} for Reject, etc.).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface JvmInterceptorBinding {

  /** @return which {@link com.macstab.chaos.jvm.api.ChaosSelector} family to build */
  JvmSelectorKind selectorKind();

  /** @return the single {@link OperationType} this annotation targets */
  OperationType operationType();
}
