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
 * Injects {@code ENFILE} ("File table overflow") into every process-management syscall intercepted
 * by libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn}, {@code pthread_create},
 * {@code waitpid}, and their variants — simultaneously, gated by {@link #probability}, modelling
 * system-wide kernel file-descriptor table saturation that affects all process lifecycle operations.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ENFILE}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = ENFILE} and returns {@code -1}
 *       (or the errno value directly for pthread_create and POSIX spawn functions) before the
 *       real kernel call executes.</li>
 *   <li>The calling code receives: {@code fork()}/{@code posix_spawn()} return {@code -1} with
 *       {@code errno = ENFILE} (23); {@code pthread_create} returns {@code ENFILE} directly;
 *       {@code strerror(ENFILE)}: "File table overflow".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code fork()}/{@code posix_spawn()} return {@code -1}/{@code ENFILE} respectively;
 *       the child process is never created; assert that the application escalates immediately to
 *       the platform team rather than attempting in-process recovery — ENFILE is the system-wide
 *       kernel fd table limit ({@code /proc/sys/fs/file-max}) and cannot be resolved by closing
 *       fds within this process alone.</li>
 *   <li>Assert that the application distinguishes ENFILE from EMFILE: EMFILE is the per-process
 *       limit (fixable in-process by closing leaked fds); ENFILE is the system-wide limit
 *       (requires operator raising {@code fs.file-max} or reducing fd usage across all processes);
 *       the escalation runbook differs and conflating the two delays resolution.</li>
 *   <li>Assert that the application logs {@code /proc/sys/fs/file-nr} at the time ENFILE is
 *       first observed — this provides the operator with the exact saturation level at the time
 *       of failure and distinguishes gradual saturation from a sudden spike.</li>
 *   <li>Assert that the application circuit-breaks process launches when ENFILE is detected,
 *       shedding load rather than continuing to attempt new process creations that will all fail.</li>
 * </ul>
 * Production failure mode: a multi-tenant host runs hundreds of containers; a rogue container
 * leaks sockets at high rate; the system-wide fd table fills; all containers on the host receive
 * ENFILE from fork and thread creation; containers that conflate ENFILE with EMFILE attempt
 * in-process fd audits that find no local leaks and do not escalate; the platform team is not
 * notified; the host remains saturated until the rogue container is evicted.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code ENFILE} (system-wide fd table overflow, controlled by {@code /proc/sys/fs/file-max}
 * and visible via {@code /proc/sys/fs/file-nr}) differs fundamentally from {@code EMFILE}
 * (per-process fd table overflow, controlled by RLIMIT_NOFILE). ENFILE fires when the kernel's
 * global fd table is full; this is a host-level condition that affects all processes simultaneously.
 * The operator runbook is: read {@code /proc/sys/fs/file-nr} (used/free/max), identify the
 * process(es) consuming the most fds via {@code ls -l /proc/*/fd | wc -l}, and either raise
 * {@code fs.file-max} or evict the leaking process.
 *
 * <p>The wildcard selector fires ENFILE across all process-management families, testing whether
 * every process-management path in the application correctly identifies ENFILE as a host-level
 * condition requiring operator escalation rather than in-process recovery. Applications that
 * implement identical recovery logic for EMFILE and ENFILE — typically "close fds and retry" —
 * will silently fail to recover from ENFILE while wasting time on fd audits that find nothing.
 *
 * <p>Process management operations that open fds internally are particularly vulnerable during
 * ENFILE conditions: posix_spawnp opens one directory per PATH element during binary search;
 * pthread_create opens an internal eventfd or pipe for thread synchronisation; fork duplicates
 * all parent fds. All of these internal fd operations fail with ENFILE when the system table
 * is full, even if the application itself has not hit its per-process limit.
 *
 * <p>Circuit-breaking on ENFILE is essential: if process launch attempts continue under ENFILE,
 * each attempt consumes a brief slice of kernel fd-table management time without succeeding,
 * adding latency to every other fd operation on the host. The application should detect ENFILE,
 * stop all process-launch attempts, alert the platform team, and wait for confirmation that
 * {@code fs.file-max} has been raised or the leaking process evicted before resuming.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEnfile(probability = 0.002)
 * class SystemFdTableSaturation {
 *   @Test
 *   void applicationCircuitBreaksAndEscalatesToPlatformTeamOnEnfile(ConnectionInfo info) {
 *     // drive workload triggering fork and thread creation; assert ENFILE triggers platform alert;
 *     // assert /proc/sys/fs/file-nr logged; assert circuit breaker stops new process launches;
 *     // assert ENFILE vs EMFILE correctly classified; no in-process fd audit attempted
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3; ENFILE from process management is
 * non-recoverable in-process so even low rates produce persistent failures if the application
 * retries — start with 1e-3 to validate escalation logic without saturating the process lifecycle.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ENFILE)
public @interface ChaosWildcardEnfile {

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
   * @ChaosWildcardEnfile(id = "primary",  probability = 0.001)
   * @ChaosWildcardEnfile(id = "replica",  probability = 0.01)
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
    ChaosWildcardEnfile[] value();
  }
}
