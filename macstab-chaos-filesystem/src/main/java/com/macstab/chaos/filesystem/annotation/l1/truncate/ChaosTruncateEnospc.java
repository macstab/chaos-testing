/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.truncate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * L1 chaos primitive: inject {@code ENOSPC} on every libchaos-io-intercepted
 * {@code truncate} syscall, gated by {@link #probability}.
 *
 * <p><strong>What this simulates:</strong> no space left on device — canary for cleanup / log-rotation / disk-pressure bugs.
 *
 * <p><strong>Scope:</strong> applies to every path inside the container. Per-path targeting
 * remains in the imperative {@code AdvancedFilesystemChaos} API. <strong>Caution:</strong>
 * filesystem-wide errors can break container init (apt-get / apk add / service start) —
 * prefer low probabilities and pair with appropriate fault-tolerance in the test target.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.filesystem.model.IoRule#errno
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.TRUNCATE, errno = Errno.ENOSPC)
public @interface ChaosTruncateEnospc {

  /** @return probability the errno fires when matched, in {@code (0.0, 1.0]} */
  double probability() default 1.0;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-io */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
