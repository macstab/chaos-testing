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
 * Injects {@code EAGAIN} ("Resource temporarily unavailable") into every process-management
 * syscall intercepted by libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn},
 * {@code pthread_create}, {@code waitpid}, and their variants — simultaneously, gated by
 * {@link #probability}, modelling transient kernel resource pressure across the entire process
 * lifecycle.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EAGAIN}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = EAGAIN} and returns {@code -1}
 *       (or the errno value directly for pthread_create and POSIX spawn functions) before the
 *       real kernel call executes.</li>
 *   <li>The calling code receives: {@code fork()} returns {@code -1} with {@code errno = EAGAIN}
 *       (11); {@code pthread_create} and {@code posix_spawn} return {@code EAGAIN} directly;
 *       {@code strerror(EAGAIN)}: "Resource temporarily unavailable".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code fork()} returns {@code -1} with {@code errno = EAGAIN}; the child process is not
 *       created; assert that the application backs off with a delay before retrying and does not
 *       retry immediately in a tight loop — EAGAIN from fork indicates uid process-count pressure
 *       (RLIMIT_NPROC) and retrying without delay will not resolve the pressure.</li>
 *   <li>{@code pthread_create} returns {@code EAGAIN} directly (not {@code -1}); assert that the
 *       calling code checks {@code if (ret != 0)} rather than {@code if (ret == -1)}; assert that
 *       the thread pool falls back to existing threads rather than blocking indefinitely waiting
 *       for a new thread slot.</li>
 *   <li>{@code posix_spawn}/{@code posix_spawnp} return {@code EAGAIN} directly; assert that the
 *       application does not call {@code waitpid} on an uninitialised pid — the child was never
 *       created; assert that the child-tracking registry is not updated on a failed spawn.</li>
 *   <li>Assert that EAGAIN retry logic is bounded: an unbounded retry loop driven by EAGAIN from
 *       WILDCARD can spin across all process-management paths simultaneously, compounding the
 *       pressure that caused the original EAGAIN.</li>
 * </ul>
 * Production failure mode: a container running under a tight RLIMIT_NPROC hits the uid process
 * ceiling; all of fork, pthread_create, and posix_spawn return EAGAIN simultaneously; the
 * application's per-family retry loops each spin independently, each attempt consuming scheduler
 * cycles without releasing process slots; the process table stays at the ceiling while the retry
 * loops consume CPU.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EAGAIN} from process-management syscalls on Linux has two main sources: RLIMIT_NPROC
 * (the per-uid process count ceiling, enforced by the kernel on fork and clone-based thread
 * creation) and virtual address space exhaustion (on 32-bit systems or systems with tight memory
 * maps, mmap for thread stacks fails with EAGAIN). Both sources affect fork and pthread_create;
 * POSIX spawn internally calls fork or clone, so it also inherits both. EAGAIN from waitpid is
 * rarer and occurs under specific kernel resource conditions.
 *
 * <p>The wildcard selector fires across all process-management families simultaneously. At low
 * probabilities, individual call sites occasionally fail with EAGAIN and each must handle it
 * independently. This validates that every process-management path in the application has correct
 * EAGAIN handling — not just the ones that developers remembered to test. Single-selector variants
 * (e.g., {@code ChaosForkEagain}) are appropriate for targeted testing of one specific path.
 *
 * <p>Return-value conventions differ by function: {@code fork()} returns {@code -1} and sets
 * {@code errno}; {@code posix_spawn}/{@code posix_spawnp} return the error code directly without
 * setting {@code errno}; {@code pthread_create} also returns the error code directly. Code that
 * checks only {@code if (ret == -1 && errno == EAGAIN)} misses EAGAIN from spawn and thread-create
 * paths. The wildcard variant exercises all of these simultaneously, exposing inconsistent error
 * handling across the process management abstraction layers.
 *
 * <p>EAGAIN is semantically retryable — unlike ENOMEM or ENOSYS — but the retry must be bounded
 * and back-off must be applied. The correct pattern is to retry after a brief delay (50–200 ms)
 * with a maximum retry count, then escalate with an alert and reduce load by rejecting incoming
 * requests rather than spawning more processes or threads.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEagain(probability = 0.002)
 * class ProcessPressureTest {
 *   @Test
 *   void allProcessManagementPathsBackOffCorrectlyOnEagain(ConnectionInfo info) {
 *     // drive mixed workload triggering fork, pthread_create, and posix_spawn;
 *     // assert bounded retry with back-off on each path; assert no tight spin loops;
 *     // assert child-tracking registry not updated on failed spawn
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3 exercises all error paths without
 * blocking startup; values above 0.05 will prevent the container init sequence from spawning
 * the threads and processes it needs; start with 1e-3 and confirm the container starts
 * successfully before increasing.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EAGAIN)
public @interface ChaosWildcardEagain {

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
   * @ChaosWildcardEagain(id = "primary",  probability = 0.001)
   * @ChaosWildcardEagain(id = "replica",  probability = 0.01)
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
    ChaosWildcardEagain[] value();
  }
}
