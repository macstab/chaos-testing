/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

/**
 * Meta-annotation attached to every memory-errno L1 annotation, declaring the libchaos-memory
 * {@link MemorySelector} and {@link MmapErrno} the annotation encodes. Read reflectively at
 * lifecycle time by {@code MemoryErrnoTranslator} to build the corresponding {@code MemoryRule}.
 *
 * <p>This is the per-annotation binding that earns the L1 tier its compile-time selector × errno
 * safety: each (selector, errno) tuple has its own annotation class with this meta hard-coded,
 * so invalid tuples (per {@link MemorySelector#validErrnos()}) simply have no annotation class.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface MemoryErrnoBinding {

  /** @return the libchaos-memory selector this annotation targets */
  MemorySelector selector();

  /** @return the errno this annotation injects */
  MmapErrno errno();
}
