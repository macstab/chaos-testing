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
 * Injects {@code ENOENT} into {@code execve} calls intercepted by libchaos-process, causing the
 * calling code to observe a no-such-file failure when attempting to replace the process image.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code ENOENT}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = ENOENT} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 2,
 *       {@code strerror}: "No such file or directory"; no new process image is loaded.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code execve} returns {@code -1}; {@code errno = ENOENT} (2); the binary path does not
 *       exist or a component of the path prefix is missing — the application must handle the
 *       failure with a clear diagnostic that includes the attempted path.</li>
 *   <li>Applications that resolve binary paths via {@code $PATH} at runtime must handle
 *       {@code ENOENT} from {@code execve} by trying the next entry in {@code $PATH} or reporting
 *       that the binary is not installed, rather than propagating the kernel error code directly
 *       to the user.</li>
 *   <li>Assert that the application's error message for {@code ENOENT} from {@code execve} is
 *       actionable for operators: it should name the binary that was not found, the full path
 *       attempted, and whether a {@code $PATH} search was performed — this distinguishes a missing
 *       installation from a misconfigured path.</li>
 * </ul>
 * Production failure mode: a container image layer that provides a helper binary (e.g. a
 * compression tool, a database client) is updated and the binary is renamed or relocated; the
 * application's hardcoded path to the binary fails with {@code ENOENT} at runtime — a deployment
 * that passes image scanning and health checks fails silently when the code path that invokes the
 * binary is first exercised.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code ENOENT} for {@code execve} when the {@code pathname} argument does not
 * name an existing file, or when a component of the path prefix is not a directory or does not
 * exist. On Linux, the kernel returns {@code ENOENT} from {@code do_open_execat} when the VFS
 * lookup fails. A second, less obvious case is script execution: when {@code execve} is invoked on
 * a script with a {@code #!} interpreter line, the kernel parses the line and calls {@code execve}
 * recursively on the interpreter path; if the interpreter path in the shebang line does not exist,
 * the outer {@code execve} returns {@code ENOENT} even though the script file itself exists.
 *
 * <p>The shebang-interpreter case is particularly treacherous: a script that uses
 * {@code #!/usr/bin/env python3} will return {@code ENOENT} from {@code execve} if {@code env}
 * is not installed at {@code /usr/bin/env} (non-standard for some minimal base images) or if
 * {@code python3} is not in {@code $PATH}. The application sees {@code ENOENT} but the script
 * file is present and readable — the error message "No such file or directory" is misleading
 * unless the diagnostic includes the attempted interpreter path.
 *
 * <p>Container image layer interoperability is a frequent production source of {@code ENOENT}:
 * when a base image and an application layer are combined in a multi-stage build, the final image
 * may be missing binaries that were present in an intermediate layer but were excluded from the
 * final copy. The binary exists in development (where the full build environment is used) but not
 * in production (where only the runtime layer is deployed).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEnoent(probability = 0.001)
 * class ExecveMissingBinaryTest {
 *   @Test
 *   void applicationReportsMissingBinaryWithActionableDiagnostic(ConnectionInfo info) {
 *     // verify error message names the missing binary and path; fallback or safe degradation
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; binary-not-found is a deployment
 * error rather than a runtime condition, so even very low rates will exercise the error path
 * without masking normal execve operations.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.ENOENT)
public @interface ChaosExecveEnoent {

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
   * @ChaosExecveEnoent(id = "primary",  probability = 0.001)
   * @ChaosExecveEnoent(id = "replica",  probability = 0.01)
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
    ChaosExecveEnoent[] value();
  }
}
