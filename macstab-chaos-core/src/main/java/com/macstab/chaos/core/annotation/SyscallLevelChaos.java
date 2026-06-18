/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.syscall.LibchaosLib;

/**
 * Opts a test class into syscall-level fault injection by declaring which {@link LibchaosLib}
 * libraries should be loaded into every container created by {@code ChaosTestingExtension}
 * <em>before</em> {@code container.start()}.
 *
 * <p><strong>Why this annotation exists.</strong> The libchaos-* libraries hook syscalls via {@code
 * LD_PRELOAD}, which the Linux dynamic loader honours <em>only at process start</em>. Preparation
 * must therefore complete before the container's main process boots. This annotation is the
 * user-visible signal that the lifecycle hand-off is in play; the extension reads it and drives
 * {@code LibchaosTransport.prepare()} into the right window.
 *
 * <p><strong>Visibility, not magic.</strong> Without this annotation, advanced syscall-level verbs
 * ({@code chaos.advanced().*} on the connection module, equivalents on disk/memory/time modules as
 * they land) raise {@code LibchaosNotPreparedException} loudly at the call site — there is no
 * silent fallback. This is intentional: the lifecycle contract is non-recoverable once the
 * container is running, and a clear error beats opaque misbehaviour.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * class MyTest {
 *
 *   @Test
 *   void simulatesDnsOutage(RedisConnectionInfo info, ConnectionChaos chaos) {
 *     ((AdvancedConnectionChaos) chaos)
 *         .failDnsResolve(container, "redis.internal", Errno.EHOSTUNREACH, 1.0);
 *     // ...
 *   }
 * }
 * }</pre>
 *
 * <p>Multiple libraries may be declared simultaneously: {@code @SyscallLevelChaos({NET, IO})}. Each
 * is prepared independently; the {@code LD_PRELOAD} composition is handled by {@code
 * LibchaosTransport}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see LibchaosLib
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface SyscallLevelChaos {

  /**
   * Libchaos libraries to load before container start.
   *
   * @return one or more libraries; empty array is permitted but has no effect
   */
  LibchaosLib[] value();
}
