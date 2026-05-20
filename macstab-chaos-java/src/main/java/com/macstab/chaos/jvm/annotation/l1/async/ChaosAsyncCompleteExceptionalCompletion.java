/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.async;

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
 * L1 chaos primitive: complete the CompletableFuture exceptionally before normal completion fires.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @JvmAgentChaos
 * @ChaosAsyncCompleteExceptionalCompletion
 * class MyTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#ASYNC_COMPLETE
 * @see com.macstab.chaos.jvm.api.ChaosSelector#async(java.util.Set)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionalCompletionTranslator")
@JvmInterceptorBinding(selectorKind = JvmSelectorKind.ASYNC, operationType = OperationType.ASYNC_COMPLETE)
public @interface ChaosAsyncCompleteExceptionalCompletion {

  /** @return failure kind for the exceptional completion */
  com.macstab.chaos.jvm.api.ChaosEffect.FailureKind failureKind()
      default com.macstab.chaos.jvm.api.ChaosEffect.FailureKind.RUNTIME;

  /** @return exception message */
  String message() default "completed exceptionally by chaos L1";

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the JVM agent is not active on the container */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
