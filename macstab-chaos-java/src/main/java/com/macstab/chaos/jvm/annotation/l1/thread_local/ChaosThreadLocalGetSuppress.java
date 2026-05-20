/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.thread_local;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * L1 chaos primitive: silently suppress the THREAD_LOCAL_GET operation; callers receive null/false/no-op per operation semantics.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @JvmAgentChaos
 * @ChaosThreadLocalGetSuppress
 * class MyTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#THREAD_LOCAL_GET
 * @see com.macstab.chaos.jvm.api.ChaosSelector#threadLocal(java.util.Set)
 */
@Repeatable(ChaosThreadLocalGetSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(selectorKind = JvmSelectorKind.THREAD_LOCAL, operationType = OperationType.THREAD_LOCAL_GET)
public @interface ChaosThreadLocalGetSuppress {


  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the JVM agent is not active on the container */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   * <pre>{@code
   * @ChaosThreadLocalGetSuppress(id = "primary",  probability = 0.001)
   * @ChaosThreadLocalGetSuppress(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD
  })
  @interface Repeatable {
    ChaosThreadLocalGetSuppress[] value();
  }
}
