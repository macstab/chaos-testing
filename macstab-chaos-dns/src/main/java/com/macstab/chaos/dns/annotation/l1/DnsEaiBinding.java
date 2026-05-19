/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Meta-annotation declaring the (selector-kind, EAI errno) tuple encoded by a DNS-EAI L1
 * annotation. Read reflectively by {@code DnsEaiTranslator}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface DnsEaiBinding {
  DnsSelectorKind selectorKind();

  EaiErrno errno();
}
