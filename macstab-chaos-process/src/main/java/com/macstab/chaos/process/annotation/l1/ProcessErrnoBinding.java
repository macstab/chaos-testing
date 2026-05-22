/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Meta-annotation declaring the (selector, errno) tuple encoded by a process-errno L1 annotation.
 * Read reflectively by {@code ProcessErrnoTranslator} at lifecycle time.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ProcessErrnoBinding {

  /**
   * @return the libchaos-process selector this annotation targets
   */
  ProcessSelector selector();

  /**
   * @return the errno this annotation injects
   */
  ProcessErrno errno();
}
