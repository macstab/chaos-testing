/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.http_client;

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
 * L1 chaos primitive: block the HTTP_CLIENT_SEND operation until released or the configured timeout elapses.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @JvmAgentChaos
 * @ChaosHttpClientSendGate
 * class MyTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#HTTP_CLIENT_SEND
 * @see com.macstab.chaos.jvm.api.ChaosSelector#httpClient(java.util.Set)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.GateTranslator")
@JvmInterceptorBinding(selectorKind = JvmSelectorKind.HTTP_CLIENT, operationType = OperationType.HTTP_CLIENT_SEND)
public @interface ChaosHttpClientSendGate {

  /** @return maximum block duration in milliseconds */
  long maxBlockMs() default 30_000L;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the JVM agent is not active on the container */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
