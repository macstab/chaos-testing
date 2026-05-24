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
 * Injects {@code ENOENT} into {@code posix_spawn} calls intercepted by libchaos-process, causing
 * the calling code to observe a no-such-file failure when attempting to spawn a new process using
 * an explicit binary path.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code ENOENT})
 * tuple. The {@code POSIX_SPAWN} selector intercepts {@code posix_spawn} calls only, leaving {@code
 * posix_spawnp} (which searches {@code $PATH}), {@code fork}, {@code execve}, and all other process
 * syscalls unaffected. Compile-time safety: invalid selector/errno combinations have no annotation
 * class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.
 *   <li>On each {@code posix_spawn} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.
 *   <li>When the trial fires, the interposer returns {@code ENOENT} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.
 *   <li>The calling code receives: return value {@code ENOENT} (2), {@code strerror}: "No such file
 *       or directory"; no child process is created; the binary path passed to spawn does not
 *       resolve to an existing executable.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code posix_spawn} returns {@code ENOENT}; no child process is created; assert that the
 *       application includes the attempted binary path in the error diagnostic — ENOENT without the
 *       path forces operators to reconstruct what path was used from process arguments, which is
 *       not always possible in production.
 *   <li>Applications that use {@code posix_spawn} with hardcoded absolute paths must handle ENOENT
 *       as a deployment error (binary missing from container image) rather than a transient
 *       condition — assert that ENOENT does not trigger a retry loop but instead surfaces a
 *       deployment-integrity alert.
 *   <li>Assert that the application distinguishes {@code posix_spawn}-ENOENT (binary not found, use
 *       absolute path) from {@code posix_spawnp}-ENOENT (binary not found in any {@code $PATH}
 *       directory, check PATH configuration) — the remediation steps differ for each case.
 * </ul>
 *
 * Production failure mode: a container image includes a tool binary at a hardcoded path; a build
 * process updates the container image but the binary is omitted from the new layer; the process
 * calls {@code posix_spawn} with the hardcoded path; spawn returns ENOENT; the application's error
 * message says "spawn failed" without the path, leaving the operations team unable to identify
 * which binary is missing from the new image.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ENOENT} from {@code posix_spawn} occurs at the exec phase in the child: the glibc
 * implementation forks a child, sets up the file-actions and attributes in the child, then calls
 * {@code execve} with the path; if the path does not exist, {@code execve} returns ENOENT, the
 * child communicates the error back to the parent via a pipe, and the parent reports ENOENT as the
 * spawn return value. The interposer fires before the fork, so the application sees ENOENT at the
 * spawn API boundary without the internal fork/exec sequence occurring.
 *
 * <p>The distinction between {@code posix_spawn} and {@code posix_spawnp} is important for ENOENT:
 * {@code posix_spawn} takes an absolute or relative path directly, so ENOENT means the specific
 * path does not exist; {@code posix_spawnp} searches each directory in {@code $PATH} for the
 * executable, so ENOENT means the binary was not found in any PATH directory. Applications that
 * switch from {@code posix_spawnp} to {@code posix_spawn} for security (avoiding PATH injection)
 * must provide the full absolute path; if they provide only a filename, ENOENT occurs because the
 * filename is not an absolute path to an existing file.
 *
 * <p>The shebang interpreter resolution adds another ENOENT source: if the binary at the specified
 * path is a script with a shebang line ({@code #!/path/to/interpreter}), the exec in the child
 * looks up the interpreter path. If the interpreter is missing (e.g. {@code /usr/bin/env} is not
 * present in a minimal container image), the exec fails with ENOENT for the interpreter, not for
 * the script itself. Applications that spawn shell scripts must ensure the interpreter path is
 * present in the container image.
 *
 * <p>The return-value convention is important: {@code posix_spawn} returns the error code directly,
 * not -1. Code that checks {@code if (ret == -1)} after {@code posix_spawn} will silently miss
 * ENOENT (2) because 2 is not -1 and not negative. The interposer returns ENOENT as the return
 * value, consistent with the POSIX specification.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEnoent(probability = 0.001)
 * class PosixSpawnMissingBinaryTest {
 *   @Test
 *   void launcherIncludesPathInDiagnosticOnEnoentAndDoesNotRetry(ConnectionInfo info) {
 *     // verify binary path in error message; ENOENT treated as deployment error; no retry
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; binary-not-found is a deployment error;
 * any non-zero probability exercises the deployment-integrity check path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.ENOENT)
public @interface ChaosPosixSpawnEnoent {

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
   * @ChaosPosixSpawnEnoent(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnEnoent(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnEnoent[] value();
  }
}
