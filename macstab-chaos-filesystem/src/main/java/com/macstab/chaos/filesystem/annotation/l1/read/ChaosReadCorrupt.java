/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.read;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoCorruptBinding;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * L1 chaos primitive: post-success corruption of {@code read} read buffers. The syscall
 * returns success but random bytes in the buffer are altered, gated by {@link #probability}.
 *
 * <p><strong>What this simulates:</strong> bit-rot on disk, in-memory corruption from bad RAM,
 * filesystem-corruption recovery edge cases. Stresses checksum validation in storage engines.
 *
 * <p><strong>Operation:</strong> restricted to READ / PREAD by {@code IoRule.requireCompatible}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.filesystem.model.IoRule#corrupt
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoCorruptTranslator")
@IoCorruptBinding(operation = IoOperation.READ)
public @interface ChaosReadCorrupt {

  /** @return per-read probability of corruption, in {@code (0.0, 1.0]} */
  double probability() default 0.001;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-io */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
