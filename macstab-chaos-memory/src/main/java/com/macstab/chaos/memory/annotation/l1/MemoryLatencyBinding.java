/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.memory.model.MemorySelector;

/**
 * Meta-annotation attached to every memory-latency L1 annotation, declaring the libchaos-memory
 * {@link MemorySelector} the annotation targets. Read reflectively at lifecycle time by {@code
 * MemoryLatencyTranslator} to build the corresponding {@code MemoryRule}.
 *
 * <p>Latency rules have no errno dimension (latency is universally compatible across selectors per
 * the libchaos-memory grammar), so the binding carries only the selector.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface MemoryLatencyBinding {

  /** @return the libchaos-memory selector this latency annotation targets */
  MemorySelector selector();
}
