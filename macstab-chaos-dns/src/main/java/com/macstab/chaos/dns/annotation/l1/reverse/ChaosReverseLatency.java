/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.reverse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.dns.annotation.l1.DnsLatencyBinding;
import com.macstab.chaos.dns.annotation.l1.DnsSelectorKind;

/**
 * L1 chaos primitive: delay every libchaos-dns-intercepted {@code getnameinfo} call by
 * {@link #delayMs} milliseconds.
 *
 * <p><strong>What this simulates:</strong> slow resolver responses (recursive lookup latency,
 * authoritative-server slowness, IPv6 fallback timeouts).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.dns.model.DnsRule#latency
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.dns.annotation.l1.translators.DnsLatencyTranslator")
@DnsLatencyBinding(selectorKind = DnsSelectorKind.REVERSE)
public @interface ChaosReverseLatency {

  /** @return latency to apply on every match, in milliseconds (non-negative) */
  long delayMs() default 100L;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-dns */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
