/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execveat;

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
 * Injects {@code E2BIG} into {@code execveat} calls intercepted by libchaos-process, causing the
 * calling code to observe an argument-list-too-long failure when attempting to replace the process
 * image relative to a directory file descriptor.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code E2BIG}) tuple.
 * The {@code EXECVEAT} selector intercepts {@code execveat} calls only (the Linux-specific
 * directory-relative exec syscall), leaving {@code execve}, {@code fork}, {@code pthread_create},
 * {@code posix_spawn}, {@code posix_spawnp}, and {@code waitpid} unaffected. Compile-time safety:
 * invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code execveat} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = E2BIG} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 7,
 *       {@code strerror}: "Argument list too long"; no new process image is loaded.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code execveat} returns {@code -1}; {@code errno = E2BIG} (7); the argument vector or
 *       environment vector combined exceeds the kernel's {@code ARG_MAX} limit — the calling code
 *       must reduce argument size or use a file-based argument passing mechanism.</li>
 *   <li>Container runtimes and sandbox tools that use {@code execveat} with {@code AT_EMPTY_PATH}
 *       (exec-by-fd) to launch processes from an already-open binary fd must handle {@code E2BIG}
 *       when the constructed environment is too large — assert that the runtime falls back to
 *       argument batching or reduces the injected environment rather than crashing.</li>
 *   <li>Assert that the application cleans up the {@code dirfd} file descriptor on {@code E2BIG}
 *       failure — the fd was opened before the exec attempt and must be explicitly closed since
 *       the exec did not proceed to replace the image and inherit fd ownership.</li>
 * </ul>
 * Production failure mode: a container runtime uses {@code execveat} to launch the container
 * entrypoint from a pre-opened fd and injects the entire Kubernetes environment variable set;
 * accumulated environment variables across multiple init containers and pod spec layers push the
 * total environment size past {@code ARG_MAX}; the exec fails with {@code E2BIG} and the
 * container enters a crash loop with no explicit error message referencing the environment size.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code execveat(dirfd, pathname, argv, envp, flags)} extends {@code execve} with a directory
 * reference: when {@code pathname} is relative, it is resolved relative to the directory opened
 * by {@code dirfd} rather than the process's current working directory. When {@code flags}
 * includes {@code AT_EMPTY_PATH} and {@code pathname} is the empty string, {@code dirfd} must
 * refer to an executable file and the kernel executes it directly (equivalent to {@code fexecve}).
 * The {@code E2BIG} failure semantics are identical to {@code execve}: the combined size of
 * {@code argv} and {@code envp} strings exceeds the {@code ARG_MAX} limit.
 *
 * <p>The {@code AT_EMPTY_PATH} pattern is used by container runtimes (runc, crun, gVisor) to
 * execute the container's entrypoint from a file descriptor rather than a path, which avoids
 * TOCTOU races between path lookup and execution. The {@code E2BIG} failure in this pattern
 * is particularly difficult to diagnose because the failing exec has no pathname in the error
 * context — the operator sees only "Argument list too long" with no binary name.
 *
 * <p>Compared with {@code execve}'s {@code E2BIG}: the failure is identical in cause and
 * remediation; the only difference is that {@code execveat} additionally requires managing
 * the {@code dirfd} lifecycle — the fd must be closed on exec failure since the exec path did
 * not proceed to the image-replacement phase where it would be inherited or closed.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatE2big(probability = 0.01)
 * class ExecveatArgumentLimitTest {
 *   @Test
 *   void runtimeHandlesE2bigAndClosesDirfdWithoutLeak(ConnectionInfo info) {
 *     // verify dirfd closed on E2BIG; runtime reduces environment or reports size error
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; {@code execveat} is typically called
 * at container startup and for each child spawn, so low probabilities exercise the error path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveatE2big.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.E2BIG)
public @interface ChaosExecveatE2big {

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
   * @ChaosExecveatE2big(id = "primary",  probability = 0.001)
   * @ChaosExecveatE2big(id = "replica",  probability = 0.01)
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
    ChaosExecveatE2big[] value();
  }
}
