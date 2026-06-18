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
 * Injects {@code ENFILE} into {@code execveat} calls intercepted by libchaos-process, causing the
 * calling code to observe a system-wide file-table exhaustion failure when attempting to replace
 * the process image relative to a directory file descriptor.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code ENFILE}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = ENFILE} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 23, {@code strerror}: "Too many
 *       open files in system"; no new process image is loaded.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code execveat} returns {@code -1}; {@code errno = ENFILE} (23); the kernel's global file
 *       table ({@code fs.file-max}) is exhausted — no process on the system can open any new file,
 *       including the binary file that exec needs to load the new image.
 *   <li>The application must close the {@code dirfd} it opened before the exec attempt even on
 *       {@code ENFILE} failure — assert that the runtime's error path includes an explicit {@code
 *       close(dirfd)} call to avoid contributing to the system-wide fd pressure.
 *   <li>Assert that the application surfaces a platform-capacity alert for {@code ENFILE} from exec
 *       and does not attempt to resolve the condition in-process (closing the application's own fds
 *       will not help when the system-wide limit is exhausted by other processes).
 * </ul>
 *
 * Production failure mode: a high-density Kubernetes node runs container runtimes that each open a
 * {@code dirfd} to the container's root filesystem for {@code execveat(AT_EMPTY_PATH)} exec; the
 * cumulative fd usage across all runtimes on the node approaches {@code fs.file-max}; a burst of
 * container launches pushes the node over the limit; all subsequent exec calls fail with {@code
 * ENFILE} and the runtimes leak their dirfds on error, creating a compounding failure where each
 * failed launch makes the next one more likely to fail.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code ENFILE} risk is compounded in the {@code execveat(AT_EMPTY_PATH)} pattern because
 * it requires the application to open the binary fd before exec — the application has already
 * consumed one fd slot for the dirfd when it encounters the {@code ENFILE} from the exec's internal
 * binary open. The application must close the dirfd in the error path; failing to do so makes the
 * fd pressure on the system worse, potentially triggering {@code ENFILE} on subsequent open and
 * exec calls.
 *
 * <p>The operational distinction from {@code EMFILE} is critical: {@code EMFILE} means the calling
 * process's own fd table is full (fixable by closing that process's leaked fds); {@code ENFILE}
 * means the system-wide kernel file table is exhausted (requires either the platform team to raise
 * {@code fs.file-max} or a reduction of aggregate fd usage across all processes on the node).
 * Applications must emit distinct diagnostics for each to enable correct operator response.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEnfile(probability = 0.001)
 * class ExecveatSystemFdExhaustionTest {
 *   @Test
 *   void runtimeClosesDirfdOnEnfileAndAlertsPlatformTeam(ConnectionInfo info) {
 *     // verify dirfd closed on ENFILE; platform alert raised; no in-process fd-leak fix attempt
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; system-wide fd exhaustion is a
 * multi-tenant event; any non-zero probability exercises the dirfd-close-on-error path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveatEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.ENFILE)
public @interface ChaosExecveatEnfile {

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
   * @ChaosExecveatEnfile(id = "primary",  probability = 0.001)
   * @ChaosExecveatEnfile(id = "replica",  probability = 0.01)
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
    ChaosExecveatEnfile[] value();
  }
}
