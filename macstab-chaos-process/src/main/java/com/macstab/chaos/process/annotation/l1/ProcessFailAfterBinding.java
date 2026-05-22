/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Meta-annotation declaring the (selector, errno) tuple encoded by a process-fail-after L1
 * annotation. Read reflectively by {@code ProcessFailAfterTranslator}.
 *
 * <p>FailAfter is libchaos-process's unique third effect kind — the first N matched calls succeed,
 * then every subsequent call fails with the encoded errno. Models resource exhaustion
 * (RLIMIT_NPROC, thread-pool capacity, fd table saturation).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ProcessFailAfterBinding {

  /**
   * @return the libchaos-process selector this annotation targets
   */
  ProcessSelector selector();

  /**
   * @return the errno injected once the counter passes the threshold
   */
  ProcessErrno errno();
}
