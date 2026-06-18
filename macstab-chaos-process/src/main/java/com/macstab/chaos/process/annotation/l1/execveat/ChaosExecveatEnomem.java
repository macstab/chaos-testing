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
 * Injects {@code ENOMEM} into {@code execveat} calls intercepted by libchaos-process, causing the
 * calling code to observe an out-of-memory failure when attempting to replace the process image
 * relative to a directory file descriptor.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code ENOMEM}) tuple.
 * The {@code EXECVEAT} selector intercepts {@code execveat} calls only (the Linux-specific
 * directory-relative exec syscall), leaving {@code execve} and all other process syscalls
 * unaffected. Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.
 *   <li>On each {@code execveat} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENOMEM} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 12, {@code strerror}: "Out of
 *       memory"; no new process image is loaded and the calling process remains unchanged.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code execveat} returns {@code -1}; {@code errno = ENOMEM} (12); the kernel cannot
 *       allocate the memory structures needed to set up the new process image — the calling process
 *       remains in its current state and the {@code dirfd} must be closed to avoid an fd leak.
 *   <li>Container runtimes using {@code execveat} to launch the entrypoint binary must handle
 *       {@code ENOMEM} as a transient memory-pressure condition — assert that the runtime closes
 *       the {@code dirfd}, reports a memory-pressure diagnostic, and retries after backoff rather
 *       than treating the failure as a permanent binary-unavailability error.
 *   <li>In fork+exec patterns using {@code execveat}, the child that receives {@code ENOMEM} must
 *       exit cleanly ({@code _exit} with a specific error code) so the parent can detect the exec
 *       failure via {@code waitpid} — assert that the runtime's fork+exec child exits without
 *       leaving a zombie process when execveat fails with ENOMEM.
 * </ul>
 *
 * Production failure mode: a container runtime opens the entrypoint binary fd ({@code dirfd}), then
 * forks and calls {@code execveat(dirfd, "", argv, envp, AT_EMPTY_PATH)} in the child; the node is
 * under memory pressure and the exec fails with {@code ENOMEM}; the child process continues in the
 * parent's image if it does not check the exec return value, producing a zombie with the parent's
 * code but no parent's state — a difficult-to-diagnose runtime defect.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code execveat}'s {@code ENOMEM} failure semantics are identical to {@code execve}: the
 * kernel fails before committing to the new image, so the calling process (or child in fork+exec)
 * remains in its current state. The additional consideration for {@code execveat} is the open
 * {@code dirfd}: since the exec did not proceed to the image-replacement phase, the fd is not
 * transferred to the new image and remains in the caller's fd table. Applications must close it
 * explicitly after an exec failure to avoid accumulating leaked fds over repeated exec attempts
 * under memory pressure.
 *
 * <p>The {@code AT_EMPTY_PATH} pattern avoids TOCTOU races by opening the binary before exec; under
 * memory pressure, the open succeeds but the exec fails — the application must handle this "open
 * but not exec'd" state correctly by closing the fd after the failure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEnomem(probability = 0.001)
 * class ExecveatMemoryPressureTest {
 *   @Test
 *   void runtimeClosesDirfdAndRetriesAfterExecveatEnomem(ConnectionInfo info) {
 *     // verify dirfd closed on ENOMEM; child exits cleanly; no zombie process; backoff retry
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; exec-ENOMEM is rare in well-provisioned
 * environments but the fork+exec zombie risk and dirfd leak make coverage valuable.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveatEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.ENOMEM)
public @interface ChaosExecveatEnomem {

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
   * @ChaosExecveatEnomem(id = "primary",  probability = 0.001)
   * @ChaosExecveatEnomem(id = "replica",  probability = 0.01)
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
    ChaosExecveatEnomem[] value();
  }
}
