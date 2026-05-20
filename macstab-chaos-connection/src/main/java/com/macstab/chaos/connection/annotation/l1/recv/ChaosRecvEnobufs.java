/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.recv;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * L1 chaos primitive: inject {@code ENOBUFS} on every libchaos-net-intercepted
 * {@code recv} syscall, gated by {@link #toxicity}.
 *
 * <p><strong>What this simulates:</strong> no buffer space available — kernel send buffers exhausted.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosRecvEnobufs(toxicity = 0.1)
 * class MyTest { ... }
 * }</pre>
 *
 * <p><strong>Scope:</strong> applies to every {@code recv} call across all endpoints.
 * For per-endpoint targeting use the imperative {@code AdvancedConnectionChaos} API.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.connection.model.NetRule#errno
 */
@Repeatable(ChaosRecvEnobufs.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.RECV, errno = Errno.ENOBUFS)
public @interface ChaosRecvEnobufs {

  /** @return probability the errno fires when matched, in {@code (0.0, 1.0]} */
  double toxicity() default 1.0;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-net */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   * <pre>{@code
   * @ChaosRecvEnobufs(id = "primary",  probability = 0.001)
   * @ChaosRecvEnobufs(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD
  })
  @interface Repeatable {
    ChaosRecvEnobufs[] value();
  }
}
