/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.jvm.api.OperationType;

/**
 * Meta-annotation declaring the {@link OperationType} (METHOD_ENTER or METHOD_EXIT) encoded by a
 * MethodSelector-targeting L1 annotation. Distinct from {@link JvmInterceptorBinding} because
 * MethodSelector requires per-annotation class / method name patterns supplied at the call site,
 * not a fixed selector kind.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface JvmMethodBinding {

  /**
   * @return either {@link OperationType#METHOD_ENTER} or {@link OperationType#METHOD_EXIT}
   */
  OperationType operationType();
}
