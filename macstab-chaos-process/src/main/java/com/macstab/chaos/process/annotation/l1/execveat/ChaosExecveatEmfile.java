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
 * Injects {@code EMFILE} into {@code execveat} calls intercepted by libchaos-process, causing the
 * calling code to observe a per-process file-descriptor-limit failure when attempting to replace
 * the process image relative to a directory file descriptor.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code EMFILE}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = EMFILE} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 24, {@code strerror}: "Too many
 *       open files"; no new process image is loaded.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code execveat} returns {@code -1}; {@code errno = EMFILE} (24); the process's fd table
 *       has reached {@code RLIMIT_NOFILE} — the exec cannot open the binary internally and fails
 *       before the image-replacement phase begins.
 *   <li>When using {@code execveat} with {@code AT_EMPTY_PATH}, the fd table exhaustion is
 *       compounded: the application must have already opened the {@code dirfd} before calling exec,
 *       consuming one fd slot; if the exec fails with {@code EMFILE}, the {@code dirfd} must be
 *       explicitly closed — assert that the application closes the {@code dirfd} on {@code EMFILE}
 *       failure rather than leaking it, which would worsen the exhaustion.
 *   <li>Assert that the application's diagnostic message for exec-EMFILE distinguishes the "fd
 *       table full" scenario from "binary not found" — the two errors require completely different
 *       operator actions (close leaked fds vs. check deployment).
 * </ul>
 *
 * Production failure mode: a container runtime opens the entrypoint binary fd for {@code
 * execveat(AT_EMPTY_PATH)} exec; the process's fd table is approaching {@code RLIMIT_NOFILE} due to
 * leaks from previous request processing; the exec fails with {@code EMFILE}; the runtime leaks the
 * {@code dirfd} it opened for the exec attempt, further worsening the fd exhaustion — creating a
 * self-reinforcing failure cascade.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code EMFILE} interaction with {@code execveat} has an important nuance: the {@code
 * AT_EMPTY_PATH} pattern requires an open fd ({@code dirfd}) before exec is attempted. This means
 * the application must allocate an fd slot just to set up the exec call; if the table is full, the
 * open fails before exec is reached. However, if the open succeeds (the application opened the
 * dirfd when the table had one slot free) and then the exec path internally needs another fd slot,
 * the exec may fail with {@code EMFILE}. The application must handle failure at either point and
 * ensure the dirfd is closed in both cases.
 *
 * <p>The dirfd leak risk is unique to {@code execveat} compared with {@code execve}: in a
 * successful exec, the kernel closes the dirfd if it has {@code FD_CLOEXEC} set (which is
 * recommended for all dirfds); in a failed exec, no close-on-exec processing occurs and the
 * application must close the fd manually in its error handling path. Applications that copy the
 * happy path fd lifecycle to the error path without adding an explicit close will leak the dirfd on
 * every exec failure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEmfile(probability = 0.001)
 * class ExecveatFdLeakTest {
 *   @Test
 *   void runtimeClosesDirfdOnEmfileAndReportsFdCount(ConnectionInfo info) {
 *     // verify dirfd closed on EMFILE; fd count in diagnostic; no retry with same fd
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; fd exhaustion is a gradual process; any
 * non-zero probability exercises the dirfd-close-on-error path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveatEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.EMFILE)
public @interface ChaosExecveatEmfile {

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
   * @ChaosExecveatEmfile(id = "primary",  probability = 0.001)
   * @ChaosExecveatEmfile(id = "replica",  probability = 0.01)
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
    ChaosExecveatEmfile[] value();
  }
}
