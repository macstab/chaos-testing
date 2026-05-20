/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.method;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmMethodBinding;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * L1 chaos primitive: throw the configured exception at {@code METHOD_ENTER} for matched methods.
 *
 * <p><strong>Pattern requirement.</strong> At least one of {@link #classPattern} or
 * {@link #methodNamePattern} must be non-blank — MethodSelector rejects the all-ANY combination
 * to prevent JVM-wide instrumentation. Patterns are prefix-matched against the binary class name
 * (dots) and bare method name respectively.
 *
 * <p><strong>Wiring requirement.</strong> The agent doesn't auto-instrument user methods; events
 * must be raised from inside an existing interceptor (Spring AOP, AspectJ, Micronaut / Quarkus,
 * ByteBuddy advice, …) calling {@code chaosRuntime.beforeMethodEnter(cls, mth)}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmMethodBinding(operationType = OperationType.METHOD_ENTER)
public @interface ChaosMethodEnterInjectException {

  /** @return prefix matched against the binary class name (e.g. {@code "com.example.service"}) */
  String classPattern() default "";

  /** @return prefix matched against the method name (e.g. {@code "save"}) */
  String methodNamePattern() default "";

  /** @return binary class name of the exception to throw */
  String exceptionClassName() default "java.io.IOException";

  /** @return exception message */
  String message() default "injected at METHOD_ENTER by chaos L1";

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the JVM agent is not active on the container */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
