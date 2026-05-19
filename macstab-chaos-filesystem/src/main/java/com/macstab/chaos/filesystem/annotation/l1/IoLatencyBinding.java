/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Meta-annotation declaring the operation encoded by an IO-latency L1 annotation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface IoLatencyBinding {
  IoOperation operation();
}
