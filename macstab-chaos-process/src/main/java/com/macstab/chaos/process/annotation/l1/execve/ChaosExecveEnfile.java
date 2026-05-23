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
 * Injects {@code ENFILE} into {@code execve} calls intercepted by libchaos-process, causing the
 * calling code to observe a system-wide file-table exhaustion failure when attempting to replace
 * the process image.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code ENFILE}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = ENFILE} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 23,
 *       {@code strerror}: "Too many open files in system"; no new process image is loaded.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code execve} returns {@code -1}; {@code errno = ENFILE} (23); the kernel's global file
 *       table ({@code fs.file-max}) is exhausted — no process on the system can open any new file
 *       until existing files are closed.</li>
 *   <li>Unlike {@code EMFILE} (per-process limit, fixable in-process), {@code ENFILE} indicates
 *       a system-wide exhaustion that cannot be resolved by the affected process — assert that the
 *       application reports {@code ENFILE} with a clear diagnostic that distinguishes it from
 *       {@code EMFILE} and instructs operators to check the platform's file handle limits.</li>
 *   <li>Assert that the application does not retry immediately on {@code ENFILE} — retrying in
 *       a tight loop when the system-wide file table is exhausted will not help and will
 *       saturate the call path; the correct response is to fail the request and surface a
 *       platform-capacity alert.</li>
 * </ul>
 * Production failure mode: a high-density Kubernetes node runs dozens of containers each with
 * thousands of open files; the kernel's {@code fs.file-max} limit is reached by the aggregate
 * file usage; any process on the node that attempts to open a new file (including via
 * {@code execve}) receives {@code ENFILE}, causing cascading failures across all containers on
 * the node simultaneously — a cross-tenant incident triggered by the aggregate file handle usage
 * of unrelated workloads.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code ENFILE} for {@code execve} when the kernel's system-wide file table
 * would be exceeded by opening the binary. The system-wide limit is governed by
 * {@code /proc/sys/fs/file-max} (tunable via {@code sysctl fs.file-max}). When this limit is
 * reached, every open operation by every process on the system fails with {@code ENFILE} until
 * files are closed system-wide. The exec path must open the binary as a file; if the global
 * table is full, this open returns {@code ENFILE} and the exec fails before any image loading.
 *
 * <p>The multi-tenant exhaustion scenario is particularly significant: in a shared Kubernetes
 * cluster, {@code fs.file-max} is a node-level resource not subject to per-pod cgroup limits.
 * A single noisy-neighbour pod with a file descriptor leak can exhaust the system-wide limit,
 * causing {@code ENFILE} errors in unrelated pods on the same node. This failure mode is invisible
 * in per-pod resource accounting and requires node-level monitoring of the file handle count
 * (via {@code /proc/sys/fs/file-nr}) to detect.
 *
 * <p>Compared with {@code EMFILE}: {@code EMFILE} indicates the per-process {@code RLIMIT_NOFILE}
 * is exhausted (fixable by the application closing leaked fds or raising its own soft limit);
 * {@code ENFILE} indicates the system-wide kernel file table is exhausted (requires platform-level
 * remediation — either reducing aggregate fd usage or raising {@code fs.file-max}). Runbooks
 * for these two errors are completely different and applications must distinguish them explicitly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEnfile(probability = 0.001)
 * class ExecveSystemFdExhaustionTest {
 *   @Test
 *   void applicationDistinguishesEnfileFromEmfileInDiagnostic(ConnectionInfo info) {
 *     // verify ENFILE triggers platform-capacity alert, not in-process fd-leak fix
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; system-wide fd exhaustion is a
 * multi-tenant event that is rare per container but high-impact when it occurs; any non-zero
 * probability exercises the dormant error path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.ENFILE)
public @interface ChaosExecveEnfile {

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
   * @ChaosExecveEnfile(id = "primary",  probability = 0.001)
   * @ChaosExecveEnfile(id = "replica",  probability = 0.01)
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
    ChaosExecveEnfile[] value();
  }
}
