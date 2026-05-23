/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessFailAfterBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * After {@link #successesBeforeFailure} successful process-management syscall invocations across
 * all intercepted families, injects {@code ENOMEM} on every subsequent call, modelling the memory
 * exhaustion threshold where a growing heap consumes available kernel memory after N successful
 * process-management operations, causing all subsequent operations to report "Out of memory".
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ENOMEM},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N intercepted
 * process-management calls (across all families — fork, execve, posix_spawn, pthread_create,
 * waitpid) succeed, then the counter trips permanently and every subsequent call returns the error
 * code until the rule is removed. Compile-time safety: invalid selector/errno/effect combinations
 * have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter shared across all intercepted syscall
 *       families; the counter does not reset automatically between test methods when the annotation
 *       is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent process-management
 *       call returns {@code -1} (or the errno value directly for pthread_create and posix_spawn)
 *       with {@code errno = ENOMEM}.</li>
 *   <li>The calling code receives: {@code fork()}/{@code execve()} return {@code -1} with
 *       {@code errno = ENOMEM} (12); {@code posix_spawn}/{@code pthread_create} return
 *       {@code ENOMEM} directly; {@code strerror(ENOMEM)}: "Out of memory".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return ENOMEM permanently; assert that the application stops all
 *       process-management attempts immediately, triggers a GC cycle or explicit memory release,
 *       sheds load by rejecting new requests, and backs off significantly longer than for EAGAIN
 *       — ENOMEM requires memory recovery, not just a brief yield.</li>
 *   <li>FAIL_AFTER models the memory exhaustion threshold: N process-management operations succeed
 *       while a growing heap consumes available memory; at call N+1 the kernel cannot allocate
 *       the required structures; all subsequent operations return ENOMEM — assert that the
 *       application detects the ENOMEM onset across all process-management families and responds
 *       with a coherent memory-recovery action rather than per-family independent retries.</li>
 *   <li>Assert that the application does not treat ENOMEM as EAGAIN: ENOMEM requires memory
 *       release before retrying; EAGAIN requires only load reduction; retrying ENOMEM without
 *       releasing memory produces immediate ENOMEM again and wastes CPU in the retry loop.</li>
 * </ul>
 * Production failure mode: a container processes bursty requests that cause heap growth; when
 * the heap approaches the container memory limit, all fork and pthread_create calls start
 * returning ENOMEM; the application's ENOMEM handler treats it identically to EAGAIN and applies
 * a short back-off without triggering GC; the retry loop itself allocates stack memory, further
 * increasing heap pressure; the container's OOM killer fires and kills the process abruptly.
 *
 * <h2>Deep technical dive</h2>
 * <p>The WILDCARD FAIL_AFTER counter charges across all process-management families simultaneously.
 * The ENOMEM phase begins when the combined traffic exhausts the counter. After the ENOMEM phase
 * starts, fork (needs page table copy), pthread_create (needs thread stack mmap), and posix_spawn
 * (needs argv/envp copy allocation) all fail simultaneously — the correct application response is
 * a single coordinated memory-recovery action, not independent per-path retries.
 *
 * <p>The counter does not reset between test methods at class scope. First test method: N
 * successful calls (normal operation while memory grows). Subsequent test methods: ENOMEM phase
 * (all process management blocked until memory is recovered). Set
 * {@link #successesBeforeFailure} to the total process-management call count during the
 * pre-exhaustion phase across all families.
 *
 * <p>ENOMEM back-off must be substantially longer than EAGAIN back-off: EAGAIN resolves when a
 * process exits and releases its kernel slot (seconds); ENOMEM resolves when the GC reclaims
 * heap memory or the OOM killer reaps another process (seconds to minutes). The recommended
 * pattern is: detect ENOMEM across any process-management path, stop all spawn/create attempts
 * for 1–5 s, trigger a GC/memory-release cycle, check available memory, and resume only if
 * memory is above a configurable threshold.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEnomemFailAfter(successesBeforeFailure = 80)
 * class MemoryExhaustionTest {
 *   @Test
 *   void applicationCoordinatesMemoryRecoveryAcrossAllProcessManagementPaths(ConnectionInfo info) {
 *     // first 80 process calls succeed; subsequent calls return ENOMEM;
 *     // verify GC triggered; verify load shedding; verify back-off longer than EAGAIN;
 *     // verify no independent per-path retries; verify no waitpid on uninit pid after spawn ENOMEM
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the total number
 * of process-management calls during normal operation before memory exhaustion; values 20–500
 * cover typical workload phases; 0 means ENOMEM fires on the very first process-management call
 * (blocks all startup process creation).
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEnomemFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ENOMEM)
public @interface ChaosWildcardEnomemFailAfter {

  /**
   * @return number of matched calls allowed to succeed before failure begins ({@code >= 0})
   */
  long successesBeforeFailure() default 0L;

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
   * @ChaosWildcardEnomemFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEnomemFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEnomemFailAfter[] value();
  }
}
