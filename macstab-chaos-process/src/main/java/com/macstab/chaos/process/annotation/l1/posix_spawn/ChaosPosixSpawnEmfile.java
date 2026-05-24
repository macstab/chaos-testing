/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawn;

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
 * Injects {@code EMFILE} into {@code posix_spawn} calls intercepted by libchaos-process, causing
 * the calling code to observe a per-process fd-table exhaustion failure when attempting to spawn a
 * new process.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code EMFILE})
 * tuple. The {@code POSIX_SPAWN} selector intercepts {@code posix_spawn} calls only, leaving {@code
 * posix_spawnp}, {@code fork}, {@code execve}, and all other process syscalls unaffected.
 * Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.
 *   <li>On each {@code posix_spawn} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.
 *   <li>When the trial fires, the interposer returns {@code EMFILE} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.
 *   <li>The calling code receives: return value {@code EMFILE} (24), {@code strerror}: "Too many
 *       open files"; the process's fd table has reached {@code RLIMIT_NOFILE}; no child process is
 *       created.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code posix_spawn} returns {@code EMFILE}; no child process is created; assert that the
 *       application reports the current fd count in the diagnostic and does not attempt to wait on
 *       the uninitialised pid output parameter after the failure.
 *   <li>{@code posix_spawn_file_actions_t} structures often include {@code dup2} or {@code open}
 *       operations that the library executes in the child — assert that the application correctly
 *       attributes EMFILE to the parent's fd table exhaustion and not to the child's file-actions
 *       sequence, since the interposer fires before the fork/exec sequence begins.
 *   <li>Assert that the application distinguishes {@code posix_spawn}-EMFILE (24, per-process fd
 *       table full, fixable by closing leaked fds in the parent process) from ENFILE (23,
 *       system-wide kernel file table exhausted, requires platform escalation) — the operator
 *       runbook differs.
 * </ul>
 *
 * Production failure mode: a subprocess launcher uses {@code posix_spawn} to execute shell
 * commands; each invocation opens pipes for stdin/stdout/stderr via file-actions; leaked pipe fds
 * from previous commands accumulate in the process's fd table; spawn begins returning EMFILE
 * intermittently; the launcher does not report the fd count and operators cannot determine whether
 * the failure is per-process or system-wide without examining /proc/pid/fd.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EMFILE} from {@code posix_spawn} occurs when the kernel cannot allocate a new fd for
 * the exec's internal binary-open or for the file-actions operations that the spawn library
 * performs in the child process. The glibc implementation of {@code posix_spawn} uses a helper
 * thread or a helper process to execute the file-actions and exec in the child; if the fd table is
 * full when this helper attempts to open or dup2, the exec fails and the error is reported to the
 * parent as EMFILE.
 *
 * <p>The return-value convention is particularly important for EMFILE: code that checks {@code if
 * (errno == EMFILE)} after {@code posix_spawn} without checking the return value is incorrect — the
 * function returns the error code directly. The interposer returns EMFILE directly as the return
 * value, consistent with the POSIX specification and the glibc implementation.
 *
 * <p>The interaction with file-actions is nuanced: if the file-actions structure includes an {@code
 * open} action and the fd table is full when the action executes in the child, the spawn fails with
 * EMFILE. If the parent's fd table is full when the spawn library internally duplicates fds for the
 * pipe communication between parent and child helper, the spawn also fails with EMFILE. Both
 * scenarios are covered by this annotation, which fires at the spawn API boundary regardless of the
 * internal phase.
 *
 * <p>Applications that use {@code posix_spawn} for pipe-based subprocess communication (capturing
 * stdout via a pipe passed through file-actions) must ensure that pipes are properly closed in both
 * the parent and child after use. Leaked pipe read-ends in the parent accumulate with each spawn
 * invocation, eventually exhausting {@code RLIMIT_NOFILE}. The EMFILE annotation exercises the
 * error path that reveals whether the application's pipe cleanup logic is correct.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEmfile(probability = 0.001)
 * class PosixSpawnFdExhaustionTest {
 *   @Test
 *   void launcherReportsFdCountOnEmfileAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // verify EMFILE reported with fd count; no waitpid on uninit pid; EMFILE vs ENFILE distinct
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; fd exhaustion is a gradual process; any
 * non-zero probability exercises the fd-leak detection path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.EMFILE)
public @interface ChaosPosixSpawnEmfile {

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
   * @ChaosPosixSpawnEmfile(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnEmfile(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnEmfile[] value();
  }
}
