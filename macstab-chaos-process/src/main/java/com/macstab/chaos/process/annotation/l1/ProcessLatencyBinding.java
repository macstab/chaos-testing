/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Meta-annotation declaring the selector encoded by a process-latency L1 annotation. Read
 * reflectively by {@code ProcessLatencyTranslator}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ProcessLatencyBinding {

  /** @return the libchaos-process selector this latency annotation targets */
  ProcessSelector selector();
}
