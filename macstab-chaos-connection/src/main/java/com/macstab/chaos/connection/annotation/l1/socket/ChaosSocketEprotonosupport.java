/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.socket;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * L1 chaos primitive: inject {@code EPROTONOSUPPORT} on every libchaos-net-intercepted
 * {@code socket} syscall, gated by {@link #toxicity}.
 *
 * <p><strong>What this simulates:</strong> protocol not supported — typical of kernel-feature mismatch (e.g. SCTP not enabled).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSocketEprotonosupport(toxicity = 0.1)
 * class MyTest { ... }
 * }</pre>
 *
 * <p><strong>Scope:</strong> applies to every {@code socket} call across all endpoints.
 * For per-endpoint targeting use the imperative {@code AdvancedConnectionChaos} API.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.connection.model.NetRule#errno
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SOCKET, errno = Errno.EPROTONOSUPPORT)
public @interface ChaosSocketEprotonosupport {

  /** @return probability the errno fires when matched, in {@code (0.0, 1.0]} */
  double toxicity() default 1.0;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-net */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
