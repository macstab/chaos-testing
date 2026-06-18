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
 * Injects {@code EINTR} into {@code recv(2)}, causing the call to return {@code -1} with {@code
 * errno = EINTR} as if a signal was delivered to the thread while it was waiting for data,
 * interrupting the blocking receive before any data was transferred.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RECV}, errno = {@code EINTR})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted {@code
 * recv} call; when it fires the interposer returns {@code -1} with {@code errno = EINTR} without
 * performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code recv} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets {@code errno =
 *       EINTR}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EINTR} is a retriable interrupt — the connection is not affected and no data was
 *       transferred; the application must retry the {@code recv} call immediately. Assert that the
 *       retry loop is unconditional and does not count EINTR against any retry budget.
 *   <li>Native C libraries that use glibc's {@code SA_RESTART} flag handle {@code EINTR}
 *       transparently by restarting the syscall; Java's runtime sets {@code SA_RESTART} for most
 *       signals, so Java applications rarely see {@code EINTR} from {@code recv} in practice. This
 *       injection tests the case where a signal handler installs without {@code SA_RESTART}.
 *   <li>Assert that the application's I/O timeout accounting correctly subtracts elapsed time when
 *       retrying after {@code EINTR}, so that a burst of EINTR interruptions does not cause the
 *       overall operation to succeed despite consuming the timeout budget.
 *   <li>Application code that mistakenly treats {@code EINTR} as a connection error will close a
 *       still-valid connection unnecessarily; assert that the connection remains open and usable
 *       after an EINTR from recv.
 * </ul>
 *
 * <p>In production, {@code EINTR} from {@code recv} occurs when signals are delivered frequently to
 * the blocking thread — common in processes that use SIGALRM for internal timers, SIGCHLD from
 * child process exits, or SIGUSR1/SIGUSR2 for custom notifications. JVM processes that use signals
 * for garbage collection pauses (G1GC) and safepoints also generate signal traffic that can
 * interrupt blocking syscalls.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies that a blocking {@code recv} interrupted by a signal shall return {@code -1}
 * with {@code errno = EINTR} if no data has been transferred. Unlike other error codes, {@code
 * EINTR} does not indicate a problem with the socket or the connection; it is simply a notification
 * that a signal was handled while the thread was waiting. The canonical response is to check
 * whether the application is shutting down (via a volatile flag) and, if not, restart the {@code
 * recv} call.
 *
 * <p>Glibc automatically restarts syscalls interrupted by signals when the signal action was
 * installed with {@code SA_RESTART}. However, signals installed without {@code SA_RESTART} (or
 * POSIX signals like {@code SIGALRM} that clear the restart flag) will cause the interrupted {@code
 * recv} to return {@code EINTR} even through glibc. The JVM uses {@code SA_RESTART} for most
 * internal signals but GC-related signals may interrupt syscalls on some JVM configurations.
 *
 * <p>Java wraps native {@code recv} calls in a loop that retries on {@code EINTR};
 * application-level Java code therefore rarely sees {@code EINTR} propagated through the Java I/O
 * API. This injection is primarily relevant for JNI code or native agents that call {@code recv}
 * directly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosRecvEintr(toxicity = 0.1)
 * class RecvEintrTest {
 *   @Test
 *   void nativeLibraryRetryLoopHandlesEintrCorrectly(ConnectionInfo info) {
 *     // assert that the retry loop retries recv on EINTR and does not close the connection
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRecvEagain
 * @see ChaosRecvEconnreset
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosRecvEintr.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.RECV, errno = Errno.EINTR)
public @interface ChaosRecvEintr {

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
   * @ChaosRecvEintr(id = "primary",  probability = 0.001)
   * @ChaosRecvEintr(id = "replica",  probability = 0.01)
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
    ChaosRecvEintr[] value();
  }
}
