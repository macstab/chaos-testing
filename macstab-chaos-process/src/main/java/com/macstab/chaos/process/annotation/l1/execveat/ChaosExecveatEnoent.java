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
 * Injects {@code ENOENT} into {@code execveat} calls intercepted by libchaos-process, causing the
 * calling code to observe a no-such-file failure when attempting to replace the process image
 * relative to a directory file descriptor.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code ENOENT}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = ENOENT} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 2, {@code strerror}: "No such
 *       file or directory"; no new process image is loaded.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code execveat} returns {@code -1}; {@code errno = ENOENT} (2); the path relative to
 *       {@code dirfd} does not resolve to an existing file — the application must handle the
 *       failure and close the {@code dirfd} to avoid a file descriptor leak.
 *   <li>Applications using {@code execveat} with a relative path and a directory fd must handle
 *       {@code ENOENT} from the relative path resolution, which may occur even when the directory
 *       fd itself is valid — assert that the error message includes both the fd number and the
 *       attempted relative path to aid operator diagnosis.
 *   <li>Note that {@code execveat} with {@code AT_EMPTY_PATH} (exec-by-fd) cannot return {@code
 *       ENOENT} from path resolution because no path is looked up; however, if the {@code dirfd}
 *       itself refers to a file that was deleted after being opened (a deleted inode still open),
 *       the exec may succeed or fail with {@code ENOENT} depending on the kernel version and
 *       filesystem — assert that both outcomes are handled.
 * </ul>
 *
 * Production failure mode: a container runtime uses {@code execveat} with a relative path to launch
 * a sidecar binary from a specific directory; the directory's content is updated during a rolling
 * deployment and the relative path no longer exists during the update window; the exec fails with
 * {@code ENOENT} for requests during the update, causing intermittent sidecar startup failures
 * without a clear path-not-found diagnostic.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code execveat} resolves the binary path as follows: if {@code flags} includes {@code
 * AT_EMPTY_PATH} and {@code pathname} is empty, the {@code dirfd} itself is the executable;
 * otherwise, if {@code pathname} is absolute, {@code dirfd} is ignored and the path is resolved
 * absolutely; if {@code pathname} is relative, it is resolved relative to the directory referred to
 * by {@code dirfd}. {@code ENOENT} is returned when the VFS lookup fails at any point in the
 * relative resolution chain.
 *
 * <p>A unique source of {@code ENOENT} from {@code execveat} is the case where {@code pathname} is
 * non-empty and {@code dirfd} refers to a deleted directory (unlinked but still open via a retained
 * fd): on Linux, some filesystems return {@code ENOENT} for relative lookup operations on a deleted
 * directory even if the directory's content was not modified. Applications that cache directory fds
 * for repeated exec calls must handle this case.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEnoent(probability = 0.001)
 * class ExecveatMissingBinaryTest {
 *   @Test
 *   void runtimeReportsMissingBinaryWithDirfdAndRelativePathInDiagnostic(ConnectionInfo info) {
 *     // verify dirfd closed on ENOENT; error includes dirfd number and relative path
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; binary-not-found during exec is a
 * deployment error, so any non-zero probability exercises the error handling path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveatEnoent.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.ENOENT)
public @interface ChaosExecveatEnoent {

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
   * @ChaosExecveatEnoent(id = "primary",  probability = 0.001)
   * @ChaosExecveatEnoent(id = "replica",  probability = 0.01)
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
    ChaosExecveatEnoent[] value();
  }
}
