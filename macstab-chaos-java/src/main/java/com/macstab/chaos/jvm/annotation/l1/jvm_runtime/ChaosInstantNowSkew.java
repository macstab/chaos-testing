/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.jvm_runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * L1 chaos primitive: skew the value returned from INSTANT_NOW.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @JvmAgentChaos
 * @ChaosInstantNowSkew
 * class MyTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#INSTANT_NOW
 * @see com.macstab.chaos.jvm.api.ChaosSelector#jvmRuntime(java.util.Set)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ClockSkewTranslator")
@JvmInterceptorBinding(selectorKind = JvmSelectorKind.JVM_RUNTIME, operationType = OperationType.INSTANT_NOW)
public @interface ChaosInstantNowSkew {

  /** @return clock offset in milliseconds; positive = future, negative = past; non-zero */
  long skewMs() default -60_000L;

  /** @return how the skew evolves over time (FIXED / DRIFT / FREEZE) */
  com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewMode mode()
      default com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewMode.FIXED;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the JVM agent is not active on the container */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
