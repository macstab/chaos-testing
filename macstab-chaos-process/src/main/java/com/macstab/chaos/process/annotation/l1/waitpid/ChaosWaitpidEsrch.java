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
 * Injects {@code ESRCH} into {@code waitpid} calls intercepted by libchaos-process, causing the
 * calling code to observe a no-such-process failure when the pid refers to a process that is not
 * in the same process group as the caller.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WAITPID}, errno = {@code ESRCH}) tuple.
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
 *   <li>When the trial fires, the interposer returns {@code -1} and sets {@code errno = ESRCH}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: return value {@code -1}, {@code errno = ESRCH} (3),
 *       {@code strerror(ESRCH)}: "No such process"; when pid is a negative value specifying a
 *       process group id, ESRCH means no process in that group exists as a child of the caller.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code waitpid} returns {@code -1} with {@code errno == ESRCH}; assert that the
 *       application treats ESRCH as a diagnostic signal indicating a pid management bug —
 *       the pid being waited for is not a child of the calling process (possibly already reaped
 *       by a concurrent waiter, or a stale pid from a previous run).</li>
 *   <li>ESRCH from waitpid on a negative pid (process group wait) indicates the process group
 *       does not exist or has no child members; assert that the application logs the process group
 *       id and does not treat ESRCH as a retryable condition — no amount of retrying will make
 *       the non-existent process group appear.</li>
 *   <li>Assert that the application distinguishes waitpid-ESRCH (no such process/group, pid
 *       management bug) from waitpid-ECHILD (no child, expected termination) and from
 *       waitpid-EINTR (signal interrupt, retry required) — ESRCH warrants an alert and diagnostic
 *       logging of the invalid pid.</li>
 * </ul>
 * Production failure mode: a process group manager calls {@code waitpid(-pgid, ...)} to wait for
 * any child in a process group; the process group id is computed from a stale registry entry;
 * waitpid returns ESRCH because the process group no longer exists; the manager treats ESRCH as
 * a transient error and retries in a loop, continuously failing with ESRCH and never cleaning up
 * the stale registry entry.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code ESRCH} from {@code waitpid} on Linux has a specific semantic: when the pid argument
 * is a negative number (specifying a process group: {@code waitpid(-pgid, ...)}), ESRCH is
 * returned when no process in that process group exists as a child of the caller, or when the
 * process group itself does not exist. When the pid is a positive specific pid, ESRCH is also
 * returned if the process does not exist at all (as opposed to ECHILD which fires when the process
 * exists but is not a child). The distinction between ESRCH and ECHILD for specific-pid waits
 * depends on kernel implementation details that vary by version.
 *
 * <p>waitpid returns -1 on error and sets errno. Code that tests
 * {@code if (ret == -1 && errno == ESRCH)} is correct. ESRCH (3) is adjacent to EINTR (4) and
 * ECHILD (10); applications that compare only the return value (-1) and dispatch on errno correctly
 * are correct, but applications that use a single "wait error" catch-all conflate ESRCH with
 * EINTR and lose the diagnostic signal.
 *
 * <p>A subtle process group management scenario: a parent forks children into a new process group
 * ({@code setpgid(child, child)}); the parent then calls {@code waitpid(-child_pgid, ...)} to
 * reap all group members; if the child exits and is reaped quickly, the process group may be
 * empty by the time the parent calls waitpid, producing ESRCH; this is distinct from ECHILD
 * (which fires when the group exists but has no waitable children) and requires checking whether
 * the group was ever created.
 *
 * <p>PID recycling can produce apparent ESRCH: a child exits and is reaped; the kernel recycles
 * the pid to a new unrelated process; a late waitpid on the old pid receives ESRCH (the new
 * process is not a child); applications that track child pids across asynchronous operations must
 * validate that the pid they are waiting for was indeed their child before interpreting ESRCH.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWaitpidEsrch(probability = 0.01)
 * class WaitpidStalePidTest {
 *   @Test
 *   void processGroupManagerAlertsOnEsrchAndCleansStaleRegistryEntry(ConnectionInfo info) {
 *     // verify ESRCH treated as non-retryable; pid/pgid logged; stale registry entry cleaned;
 *     // alert raised; ESRCH distinguished from ECHILD and EINTR
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; ESRCH from waitpid indicates a pid
 * management bug; any non-zero probability exercises the stale-pid diagnostic alert path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWaitpidEsrch.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WAITPID, errno = ProcessErrno.ESRCH)
public @interface ChaosWaitpidEsrch {

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
   * @ChaosWaitpidEsrch(id = "primary",  probability = 0.001)
   * @ChaosWaitpidEsrch(id = "replica",  probability = 0.01)
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
    ChaosWaitpidEsrch[] value();
  }
}
