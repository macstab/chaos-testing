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
 * Injects {@code E2BIG} into {@code execve} calls intercepted by libchaos-process, causing the
 * calling code to observe an argument-list-too-long failure when attempting to replace the process
 * image.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code E2BIG}) tuple. The
 * {@code EXECVE} selector intercepts {@code execve} calls only, leaving {@code fork}, {@code
 * pthread_create}, {@code posix_spawn}, {@code posix_spawnp}, {@code execveat}, and {@code waitpid}
 * unaffected. Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.
 *   <li>On each {@code execve} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = E2BIG} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 7, {@code strerror}: "Argument
 *       list too long"; no new process image is loaded.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code execve} returns {@code -1}; {@code errno = E2BIG} (7); the argument vector or
 *       environment vector exceeds the kernel's {@code ARG_MAX} limit — the application must handle
 *       the failure without leaking pre-exec file descriptors or locks.
 *   <li>Build systems, shell interpreters, and container runtimes that construct command lines
 *       programmatically must handle {@code E2BIG} by breaking long argument lists into shorter
 *       chunks (using {@code xargs}-style batching) or by writing arguments to a file and passing
 *       the file path; assert that the application uses this fallback rather than propagating the
 *       failure to the caller as an unrecoverable error.
 *   <li>Assert that the application does not leak pre-exec state — file descriptors opened before
 *       the failed {@code execve}, shared-memory mappings, and POSIX semaphores must be cleaned up
 *       correctly when the exec fails, since the process image has not been replaced and all
 *       resources remain open.
 * </ul>
 *
 * Production failure mode: a CI orchestrator or container scheduler accumulates environment
 * variables across multiple script layers (Docker {@code ENV}, Kubernetes {@code env}, Helm chart
 * values) until the total environment size exceeds {@code ARG_MAX} (128 KiB on most Linux kernels);
 * the {@code execve} that launches the application binary fails with {@code E2BIG} at container
 * startup and the container enters a crash-loop without a useful error message.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies that {@code execve} returns {@code E2BIG} when the sum of the argument list
 * and environment list sizes exceeds the system's {@code ARG_MAX} limit. On Linux, {@code ARG_MAX}
 * is defined as {@code MAX_ARG_STRLEN * MAX_ARG_STRINGS} but in practice the effective limit is
 * {@code 1/4} of the stack size (default 8 MiB), yielding approximately 2 MiB per exec call.
 * Individual strings are limited to {@code MAX_ARG_STRLEN = 131072} bytes (128 KiB).
 *
 * <p>The most common production scenario for {@code E2BIG} is environment inflation: each layer of
 * the CI/CD pipeline adds environment variables (base image, CI framework, secrets management,
 * application configuration) and the total grows silently until a deployment to a new environment
 * or a new variable pushes it over the limit. The failure is especially difficult to diagnose
 * because it occurs at {@code execve} time — the container process may log nothing before the
 * failure, and the orchestrator reports a generic startup failure.
 *
 * <p>A second source is programmatic argument construction: applications that pass file paths,
 * configuration values, or user-provided input as command-line arguments to child processes may
 * construct argument vectors that exceed {@code ARG_MAX} when input data is large. The correct
 * pattern is to check the total argument size before calling {@code execve} or to use {@code
 * /proc/sys/kernel/arg-max} (Linux-specific) to retrieve the current limit at runtime.
 *
 * <p>Compared with {@code ENOENT}: {@code E2BIG} indicates the binary was found but the argument
 * list is too large; {@code ENOENT} indicates the binary path does not exist. Both prevent the new
 * process image from loading, but the remediation is entirely different — {@code E2BIG} requires
 * argument reduction; {@code ENOENT} requires path correction.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveE2big(probability = 0.01)
 * class ExecveArgumentLimitTest {
 *   @Test
 *   void orchestratorHandlesE2bigWithArgumentBatching(ConnectionInfo info) {
 *     // verify application breaks long argument lists into batches when E2BIG is returned
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; {@code execve} is typically called
 * infrequently (at process startup and for each child spawn), so even moderate probabilities
 * exercise the error path without excessive disruption.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveE2big.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.E2BIG)
public @interface ChaosExecveE2big {

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
   * @ChaosExecveE2big(id = "primary",  probability = 0.001)
   * @ChaosExecveE2big(id = "replica",  probability = 0.01)
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
    ChaosExecveE2big[] value();
  }
}
