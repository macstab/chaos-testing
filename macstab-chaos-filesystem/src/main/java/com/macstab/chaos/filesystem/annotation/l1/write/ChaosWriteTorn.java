/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.write;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoTornBinding;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * L1 chaos primitive: tear {@code write} writes — the kernel claims success but only part of
 * the buffer is actually persisted. Gated by {@link #probability}.
 *
 * <p><strong>What this simulates:</strong> torn-write events that occur during sudden power loss,
 * kernel panic, or filesystem corruption. Pairs with checksum-on-read to surface latent
 * corruption bugs in storage engines.
 *
 * <p><strong>Operation:</strong> restricted to WRITE / PWRITE by {@code IoRule.requireCompatible}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.filesystem.model.IoRule#torn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoTornTranslator")
@IoTornBinding(operation = IoOperation.WRITE)
public @interface ChaosWriteTorn {

  /** @return per-write probability of tearing, in {@code (0.0, 1.0]} */
  double probability() default 0.001;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-io */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
