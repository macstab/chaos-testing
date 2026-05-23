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
 * all intercepted families, injects {@code ENOSYS} on every subsequent call, modelling a
 * seccomp-profile tightening scenario where the kernel's process-management syscall support is
 * revoked after N successful operations.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ENOSYS},
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
 *       with {@code errno = ENOSYS}.</li>
 *   <li>The calling code receives: {@code fork()}/{@code execve()} return {@code -1} with
 *       {@code errno = ENOSYS} (38); {@code posix_spawn}/{@code pthread_create} return
 *       {@code ENOSYS} directly; {@code strerror(ENOSYS)}: "Function not implemented".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls (across all families)
 *       proceed normally; all subsequent calls return ENOSYS permanently; assert that the
 *       application detects the ENOSYS condition and degrades gracefully rather than crashing —
 *       ENOSYS is permanent and non-retryable; no amount of waiting will restore the syscall.</li>
 *   <li>FAIL_AFTER models a seccomp-profile tightening scenario: N process-management calls
 *       succeed while the old seccomp profile is active; a hot seccomp profile reload blocks all
 *       process-management syscalls; all subsequent calls return ENOSYS — assert that the
 *       application detects the sudden ENOSYS onset and alerts operators immediately.</li>
 *   <li>Assert that the application does not call {@code waitpid} on uninitialised pids after
 *       ENOSYS from a spawn call — the child was never created; assert that thread pool expansion
 *       stops and the pool degrades to existing threads only; assert that health checks that
 *       spawn subprocesses return a DEGRADED status rather than a HEALTHY status.</li>
 * </ul>
 * Production failure mode: a container runtime applies a hot seccomp profile update that blocks
 * the clone syscall used by fork and pthread_create; the application receives ENOSYS from all
 * process-management operations starting at the Nth call; it does not distinguish ENOSYS from
 * EPERM and applies a retry loop; the retry loop spins at high rate consuming CPU without ever
 * succeeding; the container becomes CPU-saturated and is eventually killed.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code ENOSYS} is the hardest possible process-management failure: the kernel does not
 * implement the called function at all, as opposed to EPERM (implemented but forbidden) or EACCES
 * (implemented but blocked by MAC policy). Real ENOSYS from process-management syscalls occurs
 * on: WSL1 (which does not implement the full Linux syscall table), seccomp-BPF policies that
 * return ENOSYS rather than EPERM for blocked syscalls (to avoid leaking information about policy
 * scope), and QEMU user-mode emulation of cross-architecture binaries where the host kernel
 * lacks a translated syscall implementation.
 *
 * <p>The WILDCARD FAIL_AFTER counter is shared across all intercepted syscall families. For N
 * fork calls + M pthread_create calls + P posix_spawn calls, the counter charges all of them
 * together. Set {@link #successesBeforeFailure} to the total number of process-management calls
 * (across all families) that the application makes during its startup and first-request phase,
 * so that the ENOSYS phase begins during steady-state operation.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises N successful process-management
 * calls (the pre-restriction phase); subsequent test methods exercise the ENOSYS phase where
 * all process management is blocked. The test class should verify both that the application
 * operates correctly before the restriction and that it degrades gracefully after.
 *
 * <p>ENOSYS is permanent: unlike EAGAIN (retryable) or ENOMEM (retryable after GC), ENOSYS will
 * not resolve. The application must detect the ENOSYS onset, stop all process-management attempts,
 * alert operators that the kernel configuration has changed, and serve requests using only existing
 * threads and processes until the container is restarted with a compatible seccomp profile.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEnosysFailAfter(successesBeforeFailure = 50)
 * class SeccompProfileTighteningTest {
 *   @Test
 *   void applicationDegradesgracefullyAndAlertsOnEnosysOnset(ConnectionInfo info) {
 *     // first 50 process calls succeed; subsequent calls return ENOSYS;
 *     // verify ENOSYS does not trigger retry loop; verify platform alert sent;
 *     // verify thread pool degrades to existing threads only; verify health check returns DEGRADED
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * process-management calls the application makes before steady-state; values 20–200 cover typical
 * init + first-request phases; 0 means ENOSYS fires on the very first process call (blocks
 * startup entirely — useful for testing startup-failure handling).
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEnosysFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ENOSYS)
public @interface ChaosWildcardEnosysFailAfter {

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
   * @ChaosWildcardEnosysFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEnosysFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEnosysFailAfter[] value();
  }
}
