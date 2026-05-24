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
 * Injects {@code ENOENT} ("No such file or directory") into every process-management syscall
 * intercepted by libchaos-process — {@code execve}, {@code posix_spawn}, {@code posix_spawnp}, and
 * their variants — simultaneously, gated by {@link #probability}, modelling binary-not-found
 * conditions that can affect any process launch operation during rolling deployments or filesystem
 * disruptions.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ENOENT}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = ENOENT} and returns {@code -1} (or
 *       the errno value directly for pthread_create and POSIX spawn functions) before the real
 *       kernel call executes.
 *   <li>The calling code receives: {@code execve()}/{@code fork()} return {@code -1} with {@code
 *       errno = ENOENT} (2); {@code posix_spawn}/{@code posix_spawnp} return {@code ENOENT}
 *       directly; {@code strerror(ENOENT)}: "No such file or directory".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code execve()}/{@code execveat()} return {@code -1} with {@code errno = ENOENT}; the
 *       executable binary was not found at the specified path; assert that the application logs the
 *       full binary path and does not retry without verifying the path exists — ENOENT from exec is
 *       a deployment error that retrying will not resolve.
 *   <li>{@code posix_spawn}/{@code posix_spawnp} return {@code ENOENT} directly; assert that the
 *       calling code checks {@code if (ret != 0)} rather than {@code if (ret == -1)}; assert that
 *       the application does not call {@code waitpid} on an uninitialised pid — the child was never
 *       created.
 *   <li>For {@code posix_spawnp}, ENOENT means the binary name was not found in any PATH directory;
 *       for {@code posix_spawn}, ENOENT means the specific explicit path did not exist; assert that
 *       the application logs the binary name and the PATH value to help operators diagnose which
 *       deployment step failed.
 *   <li>Assert that ENOENT from thread creation or waitpid (from the wildcard) is logged with the
 *       syscall name — ENOENT from those paths indicates a wildcard rule is active and should be
 *       surfaced for diagnostic purposes.
 * </ul>
 *
 * Production failure mode: a rolling deployment replaces a binary; during the window between the
 * old binary being removed and the new binary being installed, an exec call returns ENOENT; the
 * process supervisor does not log the binary path and retries immediately; the rapid retry loop
 * fills the process table; the deployment window extends due to spawner load; the supervisor cannot
 * recover because it has no fallback path configured.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ENOENT} from {@code execve}/{@code execveat} has two primary sources: the executable
 * binary file does not exist at the specified path (most common), or the shebang interpreter
 * ({@code #!/path/to/interpreter}) in a script does not exist at its specified path. The wildcard
 * variant fires ENOENT across all process-management families, including fork and pthread_create
 * where ENOENT does not normally occur. This tests whether the application's catch-all error
 * handler correctly propagates ENOENT from unexpected call sites.
 *
 * <p>The distinction between posix_spawn and posix_spawnp ENOENT: posix_spawn takes an explicit
 * path and ENOENT means that exact path does not exist; posix_spawnp takes a binary name and
 * searches PATH directories, returning ENOENT if the binary is not found in any directory. For
 * spawnp, logging the $PATH value alongside the binary name is essential — the binary may exist but
 * not in a directory in $PATH.
 *
 * <p>ENOENT from process management is non-retryable with the same arguments — the binary path or
 * binary name will not appear between calls unless a deployment action places it there. The
 * application must log the missing path, alert the deployment system, and wait for the deployment
 * to complete before retrying. Retry loops that do not verify path existence before retrying will
 * produce sustained ENOENT failures during the deployment window.
 *
 * <p>Return-value conventions differ by function: {@code execve()}/{@code fork()} return {@code -1}
 * and set {@code errno}; {@code posix_spawn}/{@code posix_spawnp} return the error code directly
 * without setting {@code errno}. Code that checks only {@code if (ret == -1 && errno == ENOENT)}
 * misses ENOENT from spawn paths — the application silently proceeds as if the spawn succeeded,
 * then waits forever on a pid that was never populated.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEnoent(probability = 0.003)
 * class BinaryNotFoundTest {
 *   @Test
 *   void spawnHandlerLogsPathAndPathValueAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // drive workload triggering exec and spawn; assert ENOENT logged with binary path;
 *     // assert PATH logged for spawnp; assert no waitpid on uninit pid; assert deployment alert sent;
 *     // assert no retry without path verification
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3; ENOENT from spawn is non-retryable so
 * even low rates produce user-visible errors — start with 1e-3 to validate diagnostic logging;
 * values above 0.1 will prevent the container from launching any subprocesses during startup.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ENOENT)
public @interface ChaosWildcardEnoent {

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
   * @ChaosWildcardEnoent(id = "primary",  probability = 0.001)
   * @ChaosWildcardEnoent(id = "replica",  probability = 0.01)
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
    ChaosWildcardEnoent[] value();
  }
}
