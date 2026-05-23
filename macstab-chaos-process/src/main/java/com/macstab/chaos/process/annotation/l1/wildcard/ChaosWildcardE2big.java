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
 * Injects {@code E2BIG} ("Argument list too long") into every process-management syscall
 * intercepted by libchaos-process — {@code execve}, {@code execveat}, {@code posix_spawn},
 * {@code posix_spawnp}, and their variants — simultaneously, gated by {@link #probability},
 * causing any process-launch call to fail as if the kernel rejected an oversized argument vector.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code E2BIG}) tuple.
 * The {@code WILDCARD} selector intercepts every process-management syscall family simultaneously:
 * fork, execve, execveat, posix_spawn, posix_spawnp, pthread_create, and waitpid. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.</li>
 *   <li>On each intercepted syscall, a Bernoulli trial with probability {@link #probability}
 *       runs.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = E2BIG} and returns {@code -1}
 *       (or the errno value directly for {@code pthread_create} and POSIX spawn functions) before
 *       the real kernel call executes.</li>
 *   <li>The calling code receives: {@code execve}/{@code fork} return {@code -1} with
 *       {@code errno = E2BIG} (7); {@code pthread_create} and {@code posix_spawn} return {@code E2BIG}
 *       directly; {@code strerror(E2BIG)}: "Argument list too long".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code execve}/{@code execveat} return {@code -1} with {@code errno = E2BIG}; the new
 *       executable is never loaded; assert that the application reports the argument count and
 *       total byte size in the diagnostic log — identifying which call site produced the oversized
 *       argv/envp is essential for fixing an ARG_MAX regression.</li>
 *   <li>{@code posix_spawn}/{@code posix_spawnp} return {@code E2BIG} directly (not {@code -1});
 *       assert that the calling code checks {@code if (ret != 0)} and does not call
 *       {@code waitpid} on an uninitialised pid — the child was never created.</li>
 *   <li>{@code pthread_create} returns {@code E2BIG} directly; assert that the application
 *       does not treat {@code E2BIG} from thread creation as an argument-too-long error — this
 *       would indicate a misconfigured stack attribute, not an oversized argument vector.</li>
 *   <li>Assert that the application does not retry on {@code E2BIG} with the same argument
 *       vector — the kernel limit will not change between calls; the argument vector must be
 *       trimmed or the environment cleaned before retrying.</li>
 * </ul>
 * Production failure mode: a deployment script constructs an {@code execve} argument vector from
 * environment variables accumulated by a long-running supervisor; over time the environment grows
 * beyond ARG_MAX (128 kB on Linux); all subprocess launches return E2BIG; the supervisor logs a
 * generic spawn error and retries without trimming the environment; the retry loop saturates the
 * process table and the supervisor cannot recover.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code E2BIG} from process-management syscalls exclusively concerns the {@code execve}/exec
 * family and POSIX spawn: the total size of the argument vector (argv) plus environment (envp) in
 * bytes must not exceed {@code ARG_MAX} (typically 131072 bytes on Linux, queryable via
 * {@code getconf ARG_MAX}). The check is: {@code sum(strlen(argv[i])+1) + sum(strlen(envp[j])+1)}
 * plus pointer overhead must be below the kernel limit. Environment variables from the parent
 * process accumulate silently over long-running processes, and each new {@code exec} carries the
 * full environment; this is the primary source of unexpected {@code E2BIG} in production.
 *
 * <p>The wildcard selector applies {@code E2BIG} across all process-management syscalls, which
 * means it also fires on {@code fork} and {@code pthread_create} where {@code E2BIG} does not
 * normally occur. This is intentional for wildcard coverage: it tests whether the application's
 * catch-all error handler correctly propagates the error rather than silently ignoring it, even
 * when the errno is unexpected for the specific call. Single-selector variants (e.g.,
 * {@code ChaosExecveE2big}) are preferable when testing specific exec paths precisely.
 *
 * <p>The ARG_MAX limit is per-exec, not per-process: repeated {@code fork} calls without exec are
 * not affected by ARG_MAX. Only the transition to a new executable (via exec or spawn) triggers
 * the check. Applications that use large environment passthrough to child processes — common in
 * CI/CD pipelines, container entrypoints, and cloud-function runtimes — are most at risk.
 *
 * <p>Return-value conventions vary by function: {@code execve}/{@code fork} return {@code -1} and
 * set {@code errno}; {@code posix_spawn}/{@code posix_spawnp} return the error code directly
 * without setting {@code errno}; {@code pthread_create} also returns the error code directly.
 * Code that checks {@code if (ret == -1 && errno == E2BIG)} silently misses errors from spawn
 * and pthread_create — the correct check is {@code if (ret != 0)} for the latter two families.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardE2big(probability = 0.005)
 * class ArgMaxExhaustionTest {
 *   @Test
 *   void spawnHandlerLogsArgCountAndDoesNotRetryOnE2big(ConnectionInfo info) {
 *     // drive requests that trigger subprocess spawn; assert E2BIG logged with arg byte count;
 *     // assert no retry loop; assert child pid not waited on after E2BIG
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2 exercises the error path without
 * blocking startup; values above 0.1 will prevent the container init sequence from spawning any
 * subprocesses; start with 1e-3 and confirm the container starts successfully before increasing.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardE2big.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.E2BIG)
public @interface ChaosWildcardE2big {

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
   * @ChaosWildcardE2big(id = "primary",  probability = 0.001)
   * @ChaosWildcardE2big(id = "replica",  probability = 0.01)
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
    ChaosWildcardE2big[] value();
  }
}
