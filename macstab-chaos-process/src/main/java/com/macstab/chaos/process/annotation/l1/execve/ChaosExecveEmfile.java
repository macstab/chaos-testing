/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execve;

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
 * Injects {@code EMFILE} into {@code execve} calls intercepted by libchaos-process, causing the
 * calling code to observe a per-process file-descriptor-limit failure when attempting to replace
 * the process image.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code EMFILE}) tuple.
 * The {@code EXECVE} selector intercepts {@code execve} calls only, leaving {@code fork},
 * {@code pthread_create}, {@code posix_spawn}, {@code posix_spawnp}, {@code execveat}, and
 * {@code waitpid} unaffected. Compile-time safety: invalid selector/errno combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code execve} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EMFILE} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 24,
 *       {@code strerror}: "Too many open files"; no new process image is loaded.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code execve} returns {@code -1}; {@code errno = EMFILE} (24); the process's file
 *       descriptor table has reached {@code RLIMIT_NOFILE} — the application must handle the
 *       failure without assuming the binary is absent or the permissions are wrong.</li>
 *   <li>The reason {@code execve} returns {@code EMFILE} is that exec opening the new binary
 *       requires allocating a file descriptor slot internally; if the process's fd table is
 *       full at the time of the exec, the binary open fails before the exec commits. Applications
 *       that invoke child processes must ensure their own fd table has headroom before calling
 *       exec — assert that the application closes unnecessary fds before spawning children.</li>
 *   <li>Assert that the application treats {@code EMFILE} from {@code execve} as a recoverable
 *       resource error (distinct from a permanent permission failure); the correct response is
 *       to close leaked file descriptors and retry the exec or report the fd leak for operator
 *       intervention.</li>
 * </ul>
 * Production failure mode: a long-running server process accumulates file descriptor leaks over
 * hours or days; when the process attempts to spawn a helper subprocess via {@code fork} +
 * {@code execve}, the exec fails with {@code EMFILE} because the process's fd table is
 * exhausted — the helper is silently not invoked, causing a feature regression that is invisible
 * until the leaked fd count is correlated with the helper invocation failure.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EMFILE} for {@code execve} when the per-process file descriptor limit
 * (governed by {@code RLIMIT_NOFILE}) would be exceeded by opening the binary file. On Linux,
 * the exec path must open the binary as a file before loading it; this open operation allocates
 * a file descriptor slot in the calling process's fd table. If the table is full, the open fails
 * with {@code EMFILE} and the exec returns this error before modifying the process image.
 *
 * <p>The practical implication is that {@code EMFILE} from {@code execve} is a symptom of a
 * file descriptor leak in the calling process, not a property of the binary or the exec target.
 * The distinction is operationally significant: operators investigating {@code EMFILE} errors
 * from exec should check the process's current fd count (via {@code /proc/self/fd}) and
 * {@code RLIMIT_NOFILE} limit, not the binary path. Applications should log the current fd
 * count alongside the {@code EMFILE} error to make the root cause immediately apparent.
 *
 * <p>Compared with {@code ENFILE}: {@code EMFILE} indicates the per-process limit is exhausted
 * (fixable in-process by closing leaked fds, or by raising {@code RLIMIT_NOFILE}); {@code ENFILE}
 * indicates the system-wide kernel file table is exhausted (requires platform-level intervention
 * to raise {@code fs.file-max}). For exec-EMFILE, the most likely fix is an application-level
 * fd leak fix rather than a platform configuration change.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEmfile(probability = 0.001)
 * class ExecveFdExhaustionTest {
 *   @Test
 *   void helperSpawnFailsWithEmfileAndApplicationReportsCurrentFdCount(ConnectionInfo info) {
 *     // verify EMFILE from execve includes current fd count in diagnostic for leak detection
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; fd exhaustion is a gradual process,
 * so low probabilities usefully exercise the error path without blocking all exec calls.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.EMFILE)
public @interface ChaosExecveEmfile {

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
   * @ChaosExecveEmfile(id = "primary",  probability = 0.001)
   * @ChaosExecveEmfile(id = "replica",  probability = 0.01)
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
    ChaosExecveEmfile[] value();
  }
}
