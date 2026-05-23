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
 * Injects {@code ENOENT} into {@code posix_spawnp} calls intercepted by libchaos-process, causing
 * the calling code to observe a binary-not-found failure when attempting to spawn a new process via
 * {@code $PATH} lookup.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code ENOENT})
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
 *   <li>When the trial fires, the interposer returns {@code ENOENT} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.</li>
 *   <li>The calling code receives: return value {@code ENOENT} (2),
 *       {@code strerror}: "No such file or directory"; the binary name was not found in any
 *       directory listed in {@code $PATH}; no child process is created.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code posix_spawnp} returns {@code ENOENT}; no child process is created; assert that
 *       the application includes the binary name and the current {@code $PATH} value in the error
 *       diagnostic — operators cannot fix a missing binary without knowing which name was searched
 *       and which directories were checked.</li>
 *   <li>{@code posix_spawnp}-ENOENT means the binary was not found in any {@code $PATH} directory
 *       (a deployment-integrity failure), which is fundamentally different from
 *       {@code posix_spawn}-ENOENT (the explicit path supplied to the call was wrong). Assert that
 *       the application distinguishes these two cases in its error message — both require operator
 *       action but the fix differs: PATH search failure may be resolved by adding the missing
 *       directory to {@code $PATH}; explicit-path failure requires fixing the configured path.</li>
 *   <li>Assert that the application does not retry on ENOENT — binary absence is a deployment
 *       error, not a transient condition; retry is appropriate for EAGAIN but not for ENOENT;
 *       also assert that {@code waitpid} is not called on an uninitialised pid after ENOENT failure.</li>
 * </ul>
 * Production failure mode: a microservice uses {@code posix_spawnp} to invoke a helper utility by
 * name; a container image rebuild removes the utility without updating the service's runtime
 * dependency list; the service starts successfully (the utility path is checked lazily); the first
 * spawn attempt returns ENOENT; the service surfaces a generic "command failed" error without the
 * binary name or PATH, leaving operators to inspect the container image to identify the missing
 * tool.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code posix_spawnp} ENOENT is unambiguous: the glibc implementation searches each directory
 * in {@code $PATH} (obtained from {@code getenv("PATH")}) for a file named {@code file} and none
 * is found, or {@code $PATH} is empty. The PATH search occurs in the parent process before the
 * fork, opening each directory with {@code opendir} and calling {@code stat} on candidate paths.
 * ENOENT is returned after the entire PATH list is exhausted. This is in contrast to
 * {@code posix_spawn} ENOENT, where the explicit path argument does not resolve to an existing file.
 *
 * <p>POSIX spawn returns the error code directly — checking {@code if (ret < 0)} or
 * {@code if (ret == -1)} silently misses ENOENT (2). Applications that use
 * {@code if (ret != 0) handleError(ret)} are correct; applications that check {@code errno} after
 * a non-negative return are incorrect. Because ENOENT (2) is numerically small, integer comparison
 * errors that test {@code ret < 0} or {@code ret > 100} can silently swallow the error.
 *
 * <p>The interposer fires at the spawn API boundary before the PATH search executes, so no
 * directory fds are opened or leaked. In the real kernel case, the PATH search itself opens and
 * closes directory fds for each PATH element; under fd pressure (near EMFILE), the PATH search can
 * itself fail with EMFILE — a distinct failure. This annotation models the case where the PATH
 * search completes without fd problems but the binary is simply absent from all directories.
 *
 * <p>A shebang-line interpreter case: if the binary found in {@code $PATH} is a script with a
 * {@code #!} line pointing to an interpreter that does not exist, the real kernel returns ENOENT
 * from the exec step (interpreter absent), not from the PATH search. The interposer fires at the
 * API boundary and covers this interpreter-absent case as well, since the return value is identical
 * and the application cannot distinguish the two without reading the script.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEnoent(probability = 0.01)
 * class PosixSpawnpBinaryNotFoundTest {
 *   @Test
 *   void executorReportsBinaryNameAndPathOnEnoentAndDoesNotRetry(ConnectionInfo info) {
 *     // verify binary name logged; $PATH value logged; no retry; no waitpid on uninit pid;
 *     // deployment alert raised; ENOENT vs EAGAIN distinguished
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; ENOENT is a non-retryable deployment
 * error; any non-zero probability exercises the missing-binary diagnostic path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnpEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.ENOENT)
public @interface ChaosPosixSpawnpEnoent {

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
   * @ChaosPosixSpawnpEnoent(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnpEnoent(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnpEnoent[] value();
  }
}
