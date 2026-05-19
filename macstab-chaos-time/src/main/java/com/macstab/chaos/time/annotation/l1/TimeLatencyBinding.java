/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.time.model.TimeSelector;

/**
 * Meta-annotation declaring the selector encoded by a time-latency L1 annotation. Read
 * reflectively by {@code TimeLatencyTranslator}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface TimeLatencyBinding {
  TimeSelector selector();
}
