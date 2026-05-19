/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.recv;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * L1 chaos primitive: corrupt random bytes of inbound payload on {@code recv} calls at the
 * configured {@link #rate} (per-byte bit-flip probability), gated by {@link #toxicity}
 * (per-call match probability).
 *
 * <p><strong>What this simulates:</strong> mid-flight protocol corruption — typical of bad
 * NIC offloads, MTU-fragmentation edge cases, or upstream proxy bugs. Stresses checksum /
 * deserialization paths in the application protocol.
 *
 * <p><strong>Operation:</strong> implicitly {@code RECV} — corruption is only meaningful on
 * inbound payload (libchaos-net rejects CORRUPT on other operations).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.connection.model.NetRule#corrupt
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionCorruptTranslator")
public @interface ChaosRecvCorrupt {

  /** @return per-byte bit-flip probability when the rule fires, in {@code (0.0, 1.0]} */
  double rate() default 0.001;

  /** @return per-call match probability, in {@code (0.0, 1.0]} */
  double toxicity() default 1.0;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-net */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
