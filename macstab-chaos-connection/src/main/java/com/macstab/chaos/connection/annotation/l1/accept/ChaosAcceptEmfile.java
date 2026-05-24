/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.accept;

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
 * Injects {@code EMFILE} into {@code accept(2)}, causing the call to return {@code -1} with {@code
 * errno = EMFILE} as if the process has reached its per-process file descriptor limit and cannot
 * allocate a new fd for the accepted connection.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ACCEPT}, errno = {@code
 * EMFILE}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code accept} call; when it fires the interposer returns {@code -1} with {@code errno = EMFILE}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On every intercepted {@code accept} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EMFILE}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The server cannot accept new connections even though the listening socket is still active
 *       and clients are connecting; the accept queue fills up and the kernel begins rejecting new
 *       SYN packets with RST.
 *   <li>Servers must respond to {@code EMFILE} by closing idle connections to free file
 *       descriptors, activating a back-pressure mechanism, or alerting an operator — not by
 *       retrying accept indefinitely.
 *   <li>Connection pools on the client side will time out while waiting for the server to accept
 *       their connections; assert that the pool correctly identifies the failure as a server-side
 *       resource limit rather than a network failure.
 *   <li>Assert that the server emits an alert or metric that is visible to monitoring systems when
 *       it receives {@code EMFILE} from {@code accept}.
 * </ul>
 *
 * <p>In production, {@code EMFILE} from {@code accept} occurs when the server process accumulates
 * file descriptor leaks (unclosed sockets, log files, temp files) and exhausts its {@code
 * RLIMIT_NOFILE} limit. It is a leading indicator of memory pressure and resource exhaustion that
 * precedes a full process crash.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EMFILE} is the per-process fd limit ({@code RLIMIT_NOFILE}), which defaults to 1024 on
 * most Linux distributions but is raised to 65536 or higher in production container deployments.
 * When the limit is reached, any syscall that would create a new fd (including {@code accept},
 * {@code open}, {@code socket}) fails with {@code EMFILE}.
 *
 * <p>Java's JVM typically holds fds for class files, jar files, native libraries, and heap mappings
 * in addition to application sockets. A JVM process that reaches its fd limit will fail to accept
 * connections but may also fail to load new classes or write log files. This injection tests the
 * accept-specific code path in isolation, without requiring the process to actually reach its fd
 * limit.
 *
 * <p>Netty's accept loop converts {@code EMFILE} to a warning-level log entry and activates a
 * self-protection mechanism that temporarily stops accepting to allow fds to be freed. This
 * injection exercises that self-protection code path without requiring real fd exhaustion.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosAcceptEmfile(toxicity = 0.01)
 * class AcceptEmfileTest {
 *   @Test
 *   void serverActivatesBackPressureOnFileDescriptorExhaustion(ConnectionInfo info) {
 *     // assert that the server stops accepting temporarily and emits a resource-limit metric
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAcceptEnfile
 * @see ChaosAcceptEconnreset
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosAcceptEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.ACCEPT, errno = Errno.EMFILE)
public @interface ChaosAcceptEmfile {

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
   * @ChaosAcceptEmfile(id = "primary",  probability = 0.001)
   * @ChaosAcceptEmfile(id = "replica",  probability = 0.01)
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
    ChaosAcceptEmfile[] value();
  }
}
