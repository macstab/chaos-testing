/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;

/**
 * Meta-annotation declaring the (operation, errno) tuple encoded by a connection-errno L1
 * annotation. Read reflectively by {@code ConnectionErrnoTranslator}.
 *
 * <p>Endpoint is always {@link com.macstab.chaos.connection.model.Endpoint#wildcard()} at the L1
 * tier — for per-endpoint targeting use the imperative {@code AdvancedConnectionChaos} API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ConnectionErrnoBinding {
  NetOperation operation();

  Errno errno();
}
