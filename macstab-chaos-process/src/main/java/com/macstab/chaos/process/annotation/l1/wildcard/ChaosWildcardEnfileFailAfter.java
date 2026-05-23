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
 * all intercepted families, injects {@code ENFILE} on every subsequent call, modelling the
 * system-wide kernel fd table saturation curve where aggregate fd usage across all processes on
 * the host crosses {@code fs.file-max} after N successful operations.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ENFILE},
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
 *       with {@code errno = ENFILE}.</li>
 *   <li>The calling code receives: {@code fork()}/{@code posix_spawn()} return {@code -1} with
 *       {@code errno = ENFILE} (23); {@code pthread_create} returns {@code ENFILE} directly;
 *       {@code strerror(ENFILE)}: "File table overflow".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return ENFILE permanently; assert that the application circuit-breaks
 *       all process-management attempts and escalates to the platform team — ENFILE is a host-level
 *       condition that cannot be resolved by in-process fd management alone.</li>
 *   <li>FAIL_AFTER models the system saturation curve: N process-management operations succeed
 *       while the host's aggregate fd usage climbs; at call N+1 the kernel's global fd table
 *       ({@code fs.file-max}) fills; all subsequent operations return ENFILE — assert that the
 *       application logs {@code /proc/sys/fs/file-nr} at the time of first ENFILE and sends a
 *       platform alert with the saturation metrics.</li>
 *   <li>Assert that ENFILE is distinguished from EMFILE: ENFILE cannot be resolved by closing
 *       this process's fds (the kernel table is full system-wide); assert that the application
 *       does not attempt an in-process fd audit in response to ENFILE — the runbook is different.</li>
 * </ul>
 * Production failure mode: a multi-tenant host accumulates fd leaks across multiple containers;
 * the system-wide kernel fd table fills; all containers on the host start receiving ENFILE from
 * process-management operations; containers that attempt in-process fd recovery find no local
 * leaks and loop indefinitely; the platform team is not notified; the host remains saturated.
 *
 * <h2>Deep technical dive</h2>
 * <p>ENFILE is the system-wide kernel fd table overflow — controlled by
 * {@code /proc/sys/fs/file-max} and observable via {@code /proc/sys/fs/file-nr} (used/free/max).
 * Unlike EMFILE (per-process, fixable in-process), ENFILE requires operator intervention:
 * either raise {@code fs.file-max} (requires root, takes effect immediately via sysctl) or
 * identify and evict the container(s) leaking the most fds (via {@code ls /proc/*/fd | wc -l}
 * per process). The FAIL_AFTER threshold models the point at which the gradual saturation curve
 * crosses the kernel limit.
 *
 * <p>The WILDCARD counter charges across all process-management families. The ENFILE phase begins
 * when the combined call traffic exhausts the counter. Once in the ENFILE phase, all process
 * creation, thread creation, and fd-consuming spawn operations fail simultaneously — the
 * application must detect this as a host-level condition, not a local leak.
 *
 * <p>The counter does not reset between test methods at class scope. First test method: N
 * successful calls (normal operation while host fd usage climbs). Subsequent test methods: ENFILE
 * phase (all process management blocked, platform escalation required). Set
 * {@link #successesBeforeFailure} to the number of calls during the normal operating phase.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEnfileFailAfter(successesBeforeFailure = 150)
 * class SystemFdSaturation {
 *   @Test
 *   void applicationCircuitBreaksAndLogsFileNrMetricOnEnfile(ConnectionInfo info) {
 *     // first 150 process calls succeed; subsequent calls return ENFILE;
 *     // verify circuit breaker stops all process launches; verify /proc/sys/fs/file-nr logged;
 *     // verify platform alert sent; verify ENFILE vs EMFILE classified correctly;
 *     // verify no in-process fd audit attempted for ENFILE
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * process-management calls during normal operation before the host saturation threshold; values
 * 50–500 cover typical workload phases; 0 means the kernel fd table is full from the first call.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEnfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ENFILE)
public @interface ChaosWildcardEnfileFailAfter {

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
   * @ChaosWildcardEnfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEnfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEnfileFailAfter[] value();
  }
}
