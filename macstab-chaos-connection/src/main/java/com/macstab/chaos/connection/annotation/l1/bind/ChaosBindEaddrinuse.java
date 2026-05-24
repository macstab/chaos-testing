/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.bind;

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
 * Injects {@code EADDRINUSE} into {@code bind(2)}, causing the call to return {@code -1} with
 * {@code errno = EADDRINUSE} as if the requested address and port are already bound by another
 * socket on the system.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code BIND}, errno = {@code
 * EADDRINUSE}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code bind} call; when it fires the interposer returns {@code -1} with {@code errno
 * = EADDRINUSE} without performing any real kernel operation. No runtime operation-errno validation
 * is needed.
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
 *   <li>On each intercepted {@code bind} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets {@code errno =
 *       EADDRINUSE}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Server startup fails immediately if the listening port cannot be bound; services that lack
 *       a retry-on-startup path will crash rather than waiting for the port to become available.
 *   <li>Assert that the application emits a meaningful error message identifying the conflicting
 *       port rather than a generic bind failure, so that operators can diagnose the conflict.
 *   <li>Services that use {@code SO_REUSEPORT} to allow multiple workers to share a port must
 *       handle {@code EADDRINUSE} from worker sockets specially — the annotation exercises the code
 *       path that fires when the primary listener claims the port before secondary workers bind.
 *   <li>Assert that the service initiates a graceful shutdown or restart sequence on bind failure
 *       rather than spinning in a tight retry loop that causes CPU starvation.
 * </ul>
 *
 * <p>In production, {@code EADDRINUSE} on {@code bind} occurs during fast restarts when the
 * previous process instance has not yet released the port (TIME_WAIT state holds the address),
 * during port conflicts caused by misconfigured services, and when a container is restarted without
 * the underlying network namespace being recycled.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel returns {@code EADDRINUSE} from {@code bind} when the requested address-port pair
 * is already associated with an open socket. The most common case in production is the TIME_WAIT
 * state: after a TCP connection closes, the kernel holds the local address in TIME_WAIT for 2×MSL
 * (up to 120 seconds on Linux by default) to ensure that delayed segments from the old connection
 * are absorbed before a new connection reuses the same four-tuple. A server that closes and
 * immediately tries to re-bind the same port will fail with {@code EADDRINUSE} unless it set {@code
 * SO_REUSEADDR} before calling {@code bind}.
 *
 * <p>{@code SO_REUSEADDR} allows binding to a port that is in TIME_WAIT but not to a port that has
 * an active listener. {@code SO_REUSEPORT} (Linux 3.9+) additionally allows multiple sockets to
 * bind the same port for load balancing; in that case the kernel distributes incoming connections
 * across all bound sockets. When {@code SO_REUSEPORT} is used, a new listener can bind before the
 * old one closes, enabling zero-downtime restarts. This injection tests the fallback path taken
 * when neither option resolves the conflict.
 *
 * <p>Java's {@code ServerSocket} sets {@code SO_REUSEADDR} by default before binding, so Java
 * servers rarely encounter {@code EADDRINUSE} from TIME_WAIT. However, port conflicts with other
 * processes or misconfigured containers still produce this error; Java maps it to a {@code
 * BindException} (a subclass of {@code SocketException}). Application code that catches {@code
 * IOException} but not {@code BindException} specifically may apply an incorrect retry strategy.
 *
 * <p>Frameworks that embed a web server (Spring Boot, Quarkus, Micronaut) treat {@code
 * BindException} as a fatal startup error and exit with a non-zero status code and a message such
 * as "Port 8080 is already in use". This injection verifies that the startup failure is surfaced
 * correctly through the health check or process supervisor rather than being silently caught and
 * logged at a low severity.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosBindEaddrinuse(toxicity = 0.001)
 * class BindEaddrinuseTest {
 *   @Test
 *   void serverEmitsBindFailureAlertOnAddressConflict(ConnectionInfo info) {
 *     // assert that startup failure produces a meaningful error and graceful shutdown
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosBindEaddrnotavail
 * @see ChaosBindEinval
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosBindEaddrinuse.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.BIND, errno = Errno.EADDRINUSE)
public @interface ChaosBindEaddrinuse {

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
   * @ChaosBindEaddrinuse(id = "primary",  probability = 0.001)
   * @ChaosBindEaddrinuse(id = "replica",  probability = 0.01)
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
    ChaosBindEaddrinuse[] value();
  }
}
