/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.listen;

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
 * Injects {@code EADDRINUSE} into {@code listen(2)}, causing the call to return {@code -1} with
 * {@code errno = EADDRINUSE} as if another socket is already listening on the same port through the
 * {@code SO_REUSEPORT} conflict detection path.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code LISTEN}, errno = {@code
 * EADDRINUSE}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code listen} call; when it fires the interposer returns {@code -1} with {@code
 * errno = EADDRINUSE} without performing any real kernel operation. No runtime operation-errno
 * validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.NET)} on the container definition causes the
 *       extension to upload {@code libchaos-net.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code connect}, {@code accept}, {@code socket}, {@code
 *       bind}, {@code listen}, {@code shutdown}, {@code send}, {@code recv}, and {@code poll} at
 *       the dynamic-linker level.
 *   <li>On each intercepted {@code listen} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EADDRINUSE}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EADDRINUSE} from {@code listen} is rarer than from {@code bind}; it occurs in
 *       specific kernel implementations when a second {@code listen} call conflicts with an
 *       existing listener on the same port via {@code SO_REUSEPORT}; assert that the application
 *       handles it as a fatal startup error.
 *   <li>Server frameworks that call {@code listen} inside a retry loop after a bind failure will
 *       also retry on a listen failure; assert that the retry loop recognises {@code EADDRINUSE}
 *       from {@code listen} as a separate failure mode that does not resolve by retrying.
 *   <li>Assert that the application logs the failure with the port number and process information
 *       so that operators can identify which process holds the conflicting listener.
 *   <li>Services using {@code SO_REUSEPORT} to allow multiple workers to share a port must handle
 *       this error from each worker's {@code listen} call during startup; assert that a single
 *       worker's failure does not prevent the others from starting.
 * </ul>
 *
 * <p>In production, {@code EADDRINUSE} from {@code listen} can occur when multiple processes
 * attempt to listen on the same port without {@code SO_REUSEPORT}, when a container restart creates
 * a new process before the previous process's socket reaches TIME_WAIT expiry, and in specific
 * kernel versions where the {@code SO_REUSEPORT} validation conflicts with an existing listener.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Normally, {@code EADDRINUSE} is associated with {@code bind} rather than {@code listen}: the
 * kernel checks for address conflicts at bind time. However, in some kernel versions and in the
 * context of {@code SO_REUSEPORT}, the conflict check is deferred to {@code listen} time. When
 * multiple sockets bind to the same address and port with {@code SO_REUSEPORT}, the kernel
 * validates at {@code listen} time that all sockets sharing the port have compatible options; a
 * mismatch causes {@code EADDRINUSE} from {@code listen}.
 *
 * <p>This distinction is important for server framework startup logic: frameworks that catch {@code
 * EADDRINUSE} from {@code bind} and retry with {@code SO_REUSEADDR} may not handle {@code
 * EADDRINUSE} from {@code listen} correctly, since the latter indicates a different kind of
 * conflict that {@code SO_REUSEADDR} does not resolve. This injection exercises the listen-time
 * error path in isolation.
 *
 * <p>Java's {@code ServerSocket} wraps both {@code bind} and {@code listen} in a single method call
 * and maps all errors to {@code BindException}. Application code that catches {@code BindException}
 * and logs "address in use" may not distinguish whether the conflict was detected at bind or listen
 * time; both are reported with the same message "Address already in use".
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosListenEaddrinuse(toxicity = 0.001)
 * class ListenEaddrinuseTest {
 *   @Test
 *   void serverFailsFastOnListenTimeAddressConflict(ConnectionInfo info) {
 *     // assert that startup failure is reported with port identification and graceful shutdown
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosListenEinval
 * @see ChaosListenEopnotsupp
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosListenEaddrinuse.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.LISTEN, errno = Errno.EADDRINUSE)
public @interface ChaosListenEaddrinuse {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
   */
  double toxicity() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-net
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosListenEaddrinuse(id = "primary",  probability = 0.001)
   * @ChaosListenEaddrinuse(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosListenEaddrinuse[] value();
  }
}
