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
 * Injects {@code EMFILE} ("Too many open files") into every process-management syscall intercepted
 * by libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn}, {@code pthread_create},
 * {@code waitpid}, and their variants — simultaneously, gated by {@link #probability}, modelling
 * per-process file-descriptor table exhaustion affecting the entire process lifecycle.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EMFILE}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = EMFILE} and returns {@code -1}
 *       (or the errno value directly for pthread_create and POSIX spawn functions) before the
 *       real kernel call executes.</li>
 *   <li>The calling code receives: {@code fork()}/{@code posix_spawn()} return {@code -1} with
 *       {@code errno = EMFILE} (24); {@code pthread_create}/{@code posix_spawn} return
 *       {@code EMFILE} directly; {@code strerror(EMFILE)}: "Too many open files".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code fork()} returns {@code -1} with {@code errno = EMFILE}; the child inherits the
 *       parent's file-descriptor table at fork time, requiring a duplicate slot for each fd;
 *       assert that the application triggers an in-process fd audit ({@code /proc/self/fd}
 *       inventory) when EMFILE fires from fork, and does not retry without closing at least one fd.</li>
 *   <li>{@code posix_spawn}/{@code posix_spawnp} return {@code EMFILE} when the spawn helper
 *       cannot duplicate fds for the child; assert that the application does not call
 *       {@code waitpid} on an uninitialised pid — the child was never created.</li>
 *   <li>{@code pthread_create} returns {@code EMFILE} when the internal pipe or eventfd used for
 *       thread synchronisation cannot be created; assert that the thread pool decrements its
 *       active-thread count and alerts when the pool falls below minimum size.</li>
 *   <li>Assert that EMFILE is distinguished from ENFILE: EMFILE means this process's fd table
 *       is full (fixable in-process by closing leaked fds); ENFILE means the system-wide kernel
 *       fd table is full (requires platform team intervention, not in-process recovery).</li>
 * </ul>
 * Production failure mode: a connection pool leaks fds over time — each connection closes the
 * socket but forgets to close an associated epoll fd; after N cycles the process fd table fills;
 * all fork and spawn attempts return EMFILE; the application cannot launch health-check subprocesses;
 * monitoring reports the process as healthy while it silently fails to spawn new workers.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EMFILE} (per-process fd limit, RLIMIT_NOFILE) is distinct from {@code ENFILE}
 * (system-wide kernel fd table, {@code /proc/sys/fs/file-max}). EMFILE is fixable within the
 * process by identifying and closing leaked file descriptors; ENFILE requires operator intervention
 * at the platform level. The wildcard variant fires EMFILE across all process-management families,
 * testing whether each path correctly triggers the in-process fd audit and escalation path.
 *
 * <p>The relationship between fd exhaustion and process management: {@code fork()} needs to
 * duplicate the parent's entire fd table; EMFILE fires when there is no room in the child's fd
 * table slot range (which is the same limit as the parent's). {@code posix_spawnp} opens
 * directories during PATH traversal in addition to managing fds for the child; this adds one
 * additional fd consumer per PATH element, making the effective EMFILE threshold slightly lower
 * for spawnp than for spawn or fork. {@code pthread_create} needs internal fds for thread
 * synchronisation (an eventfd or pipe depending on kernel version).
 *
 * <p>In-process EMFILE recovery requires: (1) inventorying {@code /proc/self/fd} to identify
 * leaked descriptors; (2) closing the leaked descriptors; (3) retrying the failed operation.
 * Applications that retry without closing any fds will always receive EMFILE again. The retry
 * must close at least one fd before retrying, otherwise it loops indefinitely.
 *
 * <p>EMFILE from the wildcard selector exercises all process-management paths simultaneously.
 * This reveals whether different paths in the application share the same EMFILE recovery code
 * or each implement their own — inconsistent recovery leads to different paths surviving EMFILE
 * differently, which produces intermittent failures that depend on which code path is hit first.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEmfile(probability = 0.003)
 * class FdExhaustionTest {
 *   @Test
 *   void allProcessManagementPathsAuditFdsOnEmfileAndDistinguishFromEnfile(ConnectionInfo info) {
 *     // drive workload triggering fork, spawn, and thread creation; assert EMFILE triggers
 *     // /proc/self/fd audit; assert no pid waited on after failed spawn; assert thread pool
 *     // decrements count; assert EMFILE vs ENFILE correctly classified in error logs
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3; values above 0.05 may prevent the
 * container from spawning the threads it needs during startup; start with 1e-3 and confirm
 * the container starts before increasing.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EMFILE)
public @interface ChaosWildcardEmfile {

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
   * @ChaosWildcardEmfile(id = "primary",  probability = 0.001)
   * @ChaosWildcardEmfile(id = "replica",  probability = 0.01)
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
    ChaosWildcardEmfile[] value();
  }
}
