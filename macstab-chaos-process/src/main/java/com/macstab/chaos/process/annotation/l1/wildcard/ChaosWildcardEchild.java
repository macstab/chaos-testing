/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

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
 * Injects {@code ECHILD} ("No child processes") into every process-management syscall intercepted
 * by libchaos-process simultaneously, gated by {@link #probability}, causing the calling code to
 * observe a no-child-exists condition on any process-management call rather than only on
 * {@code waitpid}.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ECHILD}) tuple.
 * The {@code WILDCARD} selector intercepts every process-management syscall family simultaneously:
 * fork, execve, execveat, posix_spawn, posix_spawnp, pthread_create, and waitpid. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.</li>
 *   <li>On each intercepted syscall, a Bernoulli trial with probability {@link #probability}
 *       runs.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = ECHILD} and returns {@code -1}
 *       (or the errno value directly for pthread_create and POSIX spawn functions) before the
 *       real kernel call executes.</li>
 *   <li>The calling code receives: {@code waitpid()} returns {@code -1} with
 *       {@code errno = ECHILD} (10); {@code pthread_create}/{@code posix_spawn} return
 *       {@code ECHILD} directly; {@code strerror(ECHILD)}: "No child processes".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code waitpid()} returns {@code -1} with {@code errno = ECHILD}; this is the normal
 *       loop-termination condition for {@code waitpid(-1, WNOHANG)} drain loops; assert that the
 *       application correctly distinguishes expected ECHILD (all children reaped) from unexpected
 *       ECHILD (double-wait bug where a pid is waited on after it was already reaped by another
 *       waiter).</li>
 *   <li>{@code fork}/{@code posix_spawn}/{@code pthread_create} receiving ECHILD (via the
 *       wildcard) is unexpected; assert that the application's catch-all error handler logs the
 *       errno and the specific syscall name — ECHILD from a fork call indicates a wildcard rule
 *       is active and should be surfaced in diagnostic logs for operators to investigate.</li>
 *   <li>Assert that the application does not retry on ECHILD from spawn calls — the child was
 *       never created and ECHILD does not indicate a transient condition on the spawn path; the
 *       application should escalate rather than retry.</li>
 * </ul>
 * Production failure mode: a process supervisor uses a SIGCHLD handler with a
 * {@code waitpid(-1, WNOHANG)} drain loop; the loop does not distinguish between ECHILD as
 * loop-termination (expected) and ECHILD arising before any children were spawned (unexpected);
 * the supervisor silently discards child state on unexpected ECHILD without alerting, losing
 * track of which children successfully started.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code ECHILD} from {@code waitpid} has two distinct semantics that the application must
 * distinguish: when called as {@code waitpid(-1, WNOHANG)} in a SIGCHLD handler drain loop,
 * ECHILD is the expected loop-exit condition signalling all children have been reaped; when called
 * for a specific pid ({@code waitpid(child_pid, ...)}) and ECHILD fires, it means the pid was
 * already reaped by another waiter (double-wait bug) or the pid was recycled to a different
 * process. The wildcard variant fires ECHILD on all process-management families, which tests
 * whether the generic error handler correctly propagates ECHILD from unexpected call sites.
 *
 * <p>The wildcard selector applying ECHILD to fork and posix_spawn is unusual — these calls
 * do not normally produce ECHILD in the kernel. This is an intentional property of the wildcard
 * effect: it tests whether the application's generic errno handling is correct regardless of the
 * specific call site. Applications that have a catch-all process error handler but only unit-test
 * it for expected errnos (EAGAIN, ENOMEM) may silently swallow ECHILD from unexpected call sites.
 *
 * <p>The SIGCHLD handler + drain loop pattern is the most common source of real ECHILD: the
 * handler calls {@code waitpid(-1, WNOHANG)} in a loop until ECHILD; if ECHILD fires early (before
 * all children are collected), the remaining children become permanent zombies. Applications must
 * log a warning when ECHILD fires before the expected number of children have been reaped.
 *
 * <p>Compared with the single-selector {@code ChaosWaitpidEchild}: the wildcard variant fires
 * ECHILD across all process families, testing cross-family error handling consistency. Use the
 * single-selector variant to target the SIGCHLD handler's waitpid loop specifically; use the
 * wildcard variant to validate that ECHILD is correctly handled regardless of which process
 * management path encounters it.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEchild(probability = 0.002)
 * class EchildHandlingTest {
 *   @Test
 *   void processManagerLogsEchildFromUnexpectedCallSiteAndDoesNotSilentlySwallow(ConnectionInfo info) {
 *     // drive workload that triggers fork, spawn, and waitpid; verify ECHILD logged with
 *     // syscall name on non-waitpid paths; verify drain loop terminates correctly on ECHILD;
 *     // verify no zombie accumulation after unexpected early ECHILD
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3; ECHILD on waitpid is a normal loop
 * termination so moderate rates are acceptable; values above 0.05 may cause child-reaping loops
 * to exit before collecting all children, producing zombie accumulation.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEchild.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ECHILD)
public @interface ChaosWildcardEchild {

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
   * @ChaosWildcardEchild(id = "primary",  probability = 0.001)
   * @ChaosWildcardEchild(id = "replica",  probability = 0.01)
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
    ChaosWildcardEchild[] value();
  }
}
