/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.waitpid;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessErrnoBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Injects {@code EINTR} into {@code waitpid} calls intercepted by libchaos-process, causing the
 * calling code to observe an interrupted-system-call failure when a signal is delivered while
 * blocking in waitpid.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WAITPID}, errno = {@code EINTR}) tuple.
 * The {@code WAITPID} selector intercepts {@code waitpid} calls only, leaving {@code fork},
 * {@code posix_spawn}, and all other process syscalls unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code waitpid} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code waitpid} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer returns {@code -1} and sets {@code errno = EINTR}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: return value {@code -1}, {@code errno = EINTR} (4),
 *       {@code strerror(EINTR)}: "Interrupted system call"; a signal was caught during the wait
 *       and no child state changed while the signal was being handled.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code waitpid} returns {@code -1} with {@code errno == EINTR}; assert that the
 *       application retries the waitpid call after EINTR — EINTR means the signal handler ran
 *       and the wait may be retried immediately; the child has not yet changed state (it is not
 *       yet a zombie).</li>
 *   <li>Applications using {@code waitpid} with blocking mode (not WNOHANG) must retry on EINTR
 *       to ensure the child is eventually reaped; assert that the retry loop is bounded and checks
 *       a shutdown flag so that SIGTERM during a wait does not cause an infinite EINTR loop.</li>
 *   <li>Assert that the application's EINTR retry does not reset the accumulated wait time — the
 *       retry must continue waiting for the same child, not start a new timed wait from zero.</li>
 * </ul>
 * Production failure mode: a process manager blocks in {@code waitpid} waiting for a worker to
 * exit; SIGTERM arrives to shut down the manager; the SIGTERM handler sets a shutdown flag and
 * returns; waitpid returns EINTR; the manager retries waitpid without checking the shutdown flag;
 * the manager blocks indefinitely on the worker that will never exit because it also received
 * SIGTERM and is waiting for the manager to signal shutdown-complete.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EINTR} from {@code waitpid} occurs when the process is blocked in the wait and a
 * signal is delivered with a handler that does not use {@code SA_RESTART}. POSIX specifies that
 * waitpid may return EINTR when a signal is caught; the glibc implementation of waitpid does not
 * automatically restart the system call on EINTR (unlike read/write for SA_RESTART signals).
 * The WNOHANG flag prevents EINTR: a non-blocking waitpid returns 0 (no child ready) rather than
 * EINTR because it does not block and cannot be interrupted.
 *
 * <p>waitpid returns -1 on error and sets errno. Code that tests
 * {@code if (ret == -1 && errno == EINTR)} is correct and must loop. A common bug is handling
 * EINTR by returning an error to the caller rather than retrying — the child becomes a zombie that
 * is never reaped, accumulating zombie entries in the process table until the parent exits.
 *
 * <p>Signal-safe EINTR handling for waitpid: the retry loop must be written as
 * {@code do { ret = waitpid(pid, &status, 0); } while (ret == -1 && errno == EINTR && !shutdown_requested)}.
 * The {@code shutdown_requested} flag must be set in the signal handler as an {@code atomic_bool}
 * or {@code volatile sig_atomic_t}. Without this flag, a SIGTERM during waitpid causes an
 * EINTR loop that ignores the termination signal.
 *
 * <p>EINTR from waitpid differs from EINTR from read/write: for I/O calls, SA_RESTART causes
 * the kernel to automatically restart the call; waitpid does not benefit from SA_RESTART in the
 * same way on all platforms — applications must implement the EINTR retry explicitly for waitpid.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWaitpidEintr(probability = 0.05)
 * class WaitpidSignalInterruptTest {
 *   @Test
 *   void processManagerRetriesWaitpidOnEintrAndChecksSigtermFlag(ConnectionInfo info) {
 *     // verify EINTR causes retry not error return; retry loop checks shutdown flag;
 *     // accumulated wait time not reset on EINTR; child eventually reaped or manager shuts down cleanly
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-2 to 1e-1; EINTR from waitpid warrants explicit
 * retry; any non-zero probability exercises the EINTR retry loop which is commonly missing or
 * incorrectly implemented.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWaitpidEintr.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WAITPID, errno = ProcessErrno.EINTR)
public @interface ChaosWaitpidEintr {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-process
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosWaitpidEintr(id = "primary",  probability = 0.001)
   * @ChaosWaitpidEintr(id = "replica",  probability = 0.01)
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
    ChaosWaitpidEintr[] value();
  }
}
