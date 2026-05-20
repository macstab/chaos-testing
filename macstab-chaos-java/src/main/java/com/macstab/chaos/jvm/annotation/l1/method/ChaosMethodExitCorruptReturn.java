/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.method;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmMethodBinding;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * L1 chaos primitive: replace the return value at {@code METHOD_EXIT} for matched methods.
 *
 * <p>See {@link com.macstab.chaos.jvm.annotation.l1.method package-info} for the pattern and
 * wiring requirements.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ReturnValueCorruptionTranslator")
@JvmMethodBinding(operationType = OperationType.METHOD_EXIT)
public @interface ChaosMethodExitCorruptReturn {

  /** @return prefix matched against the binary class name */
  String classPattern() default "";

  /** @return prefix matched against the method name */
  String methodNamePattern() default "";

  /** @return strategy for the substituted return value (NULL / ZERO / EMPTY / BOUNDARY_MAX / BOUNDARY_MIN) */
  ChaosEffect.ReturnValueStrategy strategy() default ChaosEffect.ReturnValueStrategy.NULL;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the JVM agent is not active on the container */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
