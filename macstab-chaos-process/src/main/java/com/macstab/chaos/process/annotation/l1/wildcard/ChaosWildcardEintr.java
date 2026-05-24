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
 * Injects {@code EINTR} ("Interrupted system call") into every process-management syscall
 * intercepted by libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn}, {@code
 * pthread_create}, {@code waitpid}, and their variants — simultaneously, gated by {@link
 * #probability}, modelling signal-storm conditions where signals interrupt process lifecycle
 * operations across all families concurrently.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EINTR}) tuple.
 * The {@code WILDCARD} selector intercepts every process-management syscall family simultaneously:
 * fork, execve, execveat, posix_spawn, posix_spawnp, pthread_create, and waitpid. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.
 *   <li>On each intercepted syscall, a Bernoulli trial with probability {@link #probability} runs.
 *   <li>When the trial fires, the interposer sets {@code errno = EINTR} and returns {@code -1} (or
 *       the errno value directly for pthread_create and POSIX spawn functions) before the real
 *       kernel call executes.
 *   <li>The calling code receives: {@code fork()}/{@code waitpid()} return {@code -1} with {@code
 *       errno = EINTR} (4); {@code pthread_create}/{@code posix_spawn} return {@code EINTR}
 *       directly; {@code strerror(EINTR)}: "Interrupted system call".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code waitpid()} returns {@code -1} with {@code errno = EINTR}; assert that the
 *       application's EINTR retry loop is bounded and checks its shutdown flag — an unbounded EINTR
 *       retry loop that receives a SIGTERM-triggered EINTR will spin forever rather than proceeding
 *       with graceful shutdown; SA_RESTART does not automatically restart waitpid.
 *   <li>{@code fork()} returns {@code -1} with {@code errno = EINTR}; assert that the application
 *       retries the fork with a bounded retry count rather than treating EINTR as a fatal error — a
 *       signal arriving during fork is temporary and the retry will likely succeed.
 *   <li>{@code pthread_create} returns {@code EINTR} directly (not {@code -1}); assert that the
 *       calling code checks {@code if (ret != 0)} rather than {@code if (ret == -1)} — and that
 *       EINTR from thread creation triggers a bounded retry, not an error escalation.
 *   <li>Assert that the application's catch-all process error handler checks its shutdown flag on
 *       every EINTR — EINTR triggered by SIGTERM is a signal that the process should exit, and
 *       retrying indefinitely on EINTR delays shutdown indefinitely.
 * </ul>
 *
 * Production failure mode: a process manager uses a blocking {@code waitpid} in a signal-driven
 * child-reaping loop; a high-frequency health-check timer (SIGALRM) fires repeatedly; every {@code
 * waitpid} call returns EINTR; the manager's retry loop has no shutdown-flag check; when SIGTERM
 * arrives (also triggering EINTR), the manager never exits its wait loop and the container timeout
 * kills it abruptly without running cleanup handlers.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EINTR} from process-management syscalls is a normal and expected condition on Unix: any
 * blocking syscall (fork, execve, waitpid) can be interrupted by a signal whose handler does not
 * set SA_RESTART. The two most common sources in production containers are: SIGCHLD delivery when a
 * child exits (which interrupts a blocking waitpid on a different child), and periodic timer
 * signals (SIGALRM, SIGRTMIN+n) installed by runtime libraries (Java JVM, Python GIL, Go runtime).
 * Applications using the wildcard EINTR variant must have correct EINTR handling across all
 * process-management paths simultaneously.
 *
 * <p>The EINTR retry convention differs by function: for {@code fork()}/{@code waitpid()}, retry
 * with {@code while (ret == -1 && errno == EINTR && retries++ < MAX && !shutdown)}; for {@code
 * pthread_create}/{@code posix_spawn}, retry with {@code while (ret == EINTR && retries++ < MAX &&
 * !shutdown)}. Code that checks only {@code if (ret == -1 && errno == EINTR)} misses EINTR from
 * spawn and thread-create paths; those callers will propagate EINTR as a fatal error when a retry
 * would succeed.
 *
 * <p>SA_RESTART is commonly misunderstood: it causes many syscalls to be restarted automatically
 * after a signal, but waitpid is explicitly excluded from automatic restart on Linux — EINTR always
 * propagates to the caller for waitpid regardless of SA_RESTART. Applications that rely on
 * SA_RESTART to avoid EINTR retry loops in their waitpid handlers are incorrect.
 *
 * <p>The wildcard selector fires EINTR across all process families. This validates that every
 * process-management path has correct EINTR handling, not just the ones tested in isolation. It
 * also validates that retry loops across all paths check the shutdown flag, which is critical for
 * correct graceful shutdown under signal-storm conditions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEintr(probability = 0.003)
 * class SignalStormTest {
 *   @Test
 *   void allProcessManagementPathsHaveBoundedEintrRetryWithShutdownFlagCheck(ConnectionInfo info) {
 *     // drive workload triggering fork, pthread_create, posix_spawn, and waitpid;
 *     // assert bounded retry on each path; assert shutdown flag checked; assert no spin loop;
 *     // trigger graceful shutdown and assert it completes within the timeout budget
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3 exercises EINTR on all paths; values above
 * 0.1 will cause retry loops to exhaust their budgets and surface error conditions even without a
 * real signal; start with 1e-3 to validate EINTR handling without saturating retry budgets.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEintr.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EINTR)
public @interface ChaosWildcardEintr {

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
   * @ChaosWildcardEintr(id = "primary",  probability = 0.001)
   * @ChaosWildcardEintr(id = "replica",  probability = 0.01)
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
    ChaosWildcardEintr[] value();
  }
}
