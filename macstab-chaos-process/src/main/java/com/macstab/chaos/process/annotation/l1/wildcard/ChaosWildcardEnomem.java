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
 * Injects {@code ENOMEM} ("Out of memory") into every process-management syscall intercepted by
 * libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn}, {@code pthread_create},
 * {@code waitpid}, and their variants — simultaneously, gated by {@link #probability}, modelling
 * kernel memory exhaustion that prevents all process lifecycle operations simultaneously.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ENOMEM}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = ENOMEM} and returns {@code -1} (or
 *       the errno value directly for pthread_create and POSIX spawn functions) before the real
 *       kernel call executes.
 *   <li>The calling code receives: {@code fork()}/{@code execve()} return {@code -1} with {@code
 *       errno = ENOMEM} (12); {@code pthread_create}/{@code posix_spawn} return {@code ENOMEM}
 *       directly; {@code strerror(ENOMEM)}: "Out of memory".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code fork()} returns {@code -1} with {@code errno = ENOMEM}; the child process is never
 *       created; the kernel could not copy the parent's page tables or allocate the child's task
 *       struct; assert that the application backs off (longer than EAGAIN — ENOMEM indicates
 *       genuine memory pressure requiring GC or memory release before retrying).
 *   <li>{@code pthread_create} returns {@code ENOMEM} directly; the kernel could not allocate the
 *       thread stack via mmap or the NPTL internal structures; assert that the thread pool
 *       decrements its active-thread count and backs off rather than retrying immediately; assert
 *       that the pool alerts when it cannot maintain minimum thread count.
 *   <li>{@code posix_spawn}/{@code posix_spawnp} return {@code ENOMEM} directly; the glibc spawn
 *       helper allocation for argv/envp copies or file-actions failed; assert that the application
 *       does not call {@code waitpid} on an uninitialised pid — the child was never created.
 *   <li>Assert that the application does not treat ENOMEM as EAGAIN — ENOMEM requires GC, heap
 *       compaction, or explicit memory release before retrying; an immediate retry after ENOMEM
 *       without releasing memory will produce ENOMEM again.
 * </ul>
 *
 * Production failure mode: a container's heap grows under bursty load, consuming most available
 * memory; the OOM killer has not yet fired; all fork and pthread_create calls return ENOMEM; the
 * application's process pool treats ENOMEM identically to EAGAIN and retries immediately without
 * triggering GC; the retry loops themselves consume stack memory, accelerating OOM; the OOM killer
 * eventually kills the container abruptly.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ENOMEM} from process-management syscalls has multiple kernel-level sources: for {@code
 * fork()}, the kernel must allocate a new task struct, copy the parent's page tables, and create a
 * new mm_struct; any of these can fail under memory pressure. For {@code pthread_create}, the
 * kernel must mmap the thread stack and allocate a task struct (clone-based). For POSIX spawn, the
 * glibc helper allocates heap buffers to copy argv/envp for the child; under extreme memory
 * pressure even these small allocations fail. For thread stacks, the default stack size (8 MB on
 * Linux) requires a successful mmap; a smaller thread stack (set via pthread_attr) is less likely
 * to fail with ENOMEM but can still fail under slab allocator exhaustion.
 *
 * <p>The wildcard selector fires ENOMEM across all process families simultaneously. In real
 * memory-pressure scenarios, all of these paths fail at roughly the same time. The wildcard variant
 * tests whether the application's response to simultaneous ENOMEM across all process management
 * paths is coherent — does it trigger a single memory-release action and back off all paths, or
 * does each path independently retry and compete for the limited memory?
 *
 * <p>The correct response to ENOMEM from process management: (1) stop all new process/thread
 * creation immediately; (2) trigger a GC cycle or explicit heap release; (3) shed load by rejecting
 * new requests; (4) wait for memory to recover (monitor RSS or available memory); (5) resume
 * process creation when memory is available. ENOMEM back-off should be significantly longer than
 * EAGAIN back-off — 500 ms to 5 s rather than 50–200 ms.
 *
 * <p>Return-value conventions differ by function: {@code fork()}/{@code execve()} return {@code -1}
 * and set {@code errno}; {@code pthread_create}/{@code posix_spawn} return the error code directly.
 * Code that checks only {@code if (ret == -1 && errno == ENOMEM)} misses ENOMEM from spawn and
 * thread-create paths — those callers proceed as if the operation succeeded.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEnomem(probability = 0.002)
 * class MemoryPressureTest {
 *   @Test
 *   void allProcessManagementPathsBackOffAndTriggerGcOnEnomem(ConnectionInfo info) {
 *     // drive workload triggering fork, pthread_create, and posix_spawn;
 *     // assert ENOMEM triggers GC; assert back-off longer than EAGAIN; assert load shedding;
 *     // assert no waitpid on uninit pid; assert thread pool alerts on min-size breach
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 2e-3 mirrors realistic memory-pressure failure
 * rates; values above 1e-2 will prevent the container from spawning the threads it needs during
 * startup; start with 1e-4 and confirm the container starts successfully.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ENOMEM)
public @interface ChaosWildcardEnomem {

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
   * @ChaosWildcardEnomem(id = "primary",  probability = 0.001)
   * @ChaosWildcardEnomem(id = "replica",  probability = 0.01)
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
    ChaosWildcardEnomem[] value();
  }
}
