/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawnp;

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
 * Injects {@code EMFILE} into {@code posix_spawnp} calls intercepted by libchaos-process, causing
 * the calling code to observe a per-process fd-table exhaustion failure when attempting to spawn a
 * new process via {@code $PATH} lookup.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code EMFILE})
 * tuple. The {@code POSIX_SPAWNP} selector intercepts {@code posix_spawnp} calls only, leaving
 * {@code posix_spawn}, {@code fork}, and all other process syscalls unaffected. Compile-time safety:
 * invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawnp} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code posix_spawnp} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer returns {@code EMFILE} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.</li>
 *   <li>The calling code receives: return value {@code EMFILE} (24),
 *       {@code strerror}: "Too many open files"; the process's fd table has reached
 *       {@code RLIMIT_NOFILE}; no child process is created.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code posix_spawnp} returns {@code EMFILE}; no child process is created; assert that
 *       the application reports the current fd count in the diagnostic and does not call
 *       {@code waitpid} on an uninitialised pid after the failure.</li>
 *   <li>The {@code $PATH} search in {@code posix_spawnp} opens directory fds to traverse PATH
 *       directories; when the fd table is nearly full, the PATH search itself may consume the
 *       last remaining slots — assert that the application detects fd exhaustion at or before
 *       the spawn call rather than only after seeing the EMFILE return value.</li>
 *   <li>Assert that the application distinguishes {@code posix_spawnp}-EMFILE (24, per-process
 *       fd table full, fixable by closing leaked fds) from ENFILE (23, system-wide, requires
 *       platform escalation) — the operator runbook differs.</li>
 * </ul>
 * Production failure mode: a shell command executor uses {@code posix_spawnp} to run utilities;
 * leaked pipe fds from previous command captures accumulate; spawn returns EMFILE; the executor
 * does not report the fd count, leaving operators unable to determine whether the failure is
 * per-process or system-wide without inspecting /proc/pid/fd.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EMFILE} from {@code posix_spawnp} can originate from the {@code $PATH} directory
 * traversal phase (opening directories consumes fd slots) or from the spawn's internal
 * parent-child communication pipe allocation. The interposer fires at the API boundary, covering
 * both cases. POSIX spawn returns the error code directly — checking {@code if (ret < 0)} misses
 * EMFILE (24). Applications using {@code posix_spawnp} with pipe-based subprocess communication
 * must close pipe read-ends after each child exits to prevent fd leak accumulation. Unlike
 * {@code posix_spawn}, the PATH search in {@code posix_spawnp} adds fd consumption during the
 * search itself, which means the effective EMFILE threshold may be slightly lower for spawnp.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEmfile(probability = 0.001)
 * class PosixSpawnpFdExhaustionTest {
 *   @Test
 *   void executorReportsFdCountOnEmfileAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // verify EMFILE reported with fd count; no waitpid on uninit pid; EMFILE vs ENFILE distinct
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; fd exhaustion is a gradual process;
 * any non-zero probability exercises the fd-leak detection path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnpEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.EMFILE)
public @interface ChaosPosixSpawnpEmfile {

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
   * @ChaosPosixSpawnpEmfile(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnpEmfile(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnpEmfile[] value();
  }
}
