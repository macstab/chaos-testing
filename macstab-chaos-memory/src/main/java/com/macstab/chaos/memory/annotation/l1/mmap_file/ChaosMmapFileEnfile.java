/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap_file;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.memory.annotation.l1.MemoryErrnoBinding;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

/**
 * Injects {@code ENFILE} into file-backed {@code mmap} calls intercepted by libchaos-memory,
 * causing the calling code to observe a system-wide file-table-exhaustion failure when attempting
 * to establish a file-backed memory mapping.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code ENFILE}) tuple.
 * The {@code MMAP_FILE} selector intercepts only file-backed {@code mmap} calls (those without
 * {@code MAP_ANONYMOUS}), leaving anonymous allocations unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each file-backed {@code mmap} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENFILE} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 23, {@code strerror}:
 *       "Too many open files in system".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = ENFILE} (23); the application must
 *       distinguish this from {@code EMFILE} — {@code ENFILE} is a system-wide limit requiring
 *       host-level intervention; closing fds within the process will not resolve it.
 *   <li>Applications should log a message that explicitly names "system file table full" or
 *       "ENFILE" rather than just "mmap failed" — assert that the structured error message contains
 *       enough context for an operator to identify the correct escalation path.
 *   <li>Assert that the application does not retry indefinitely — {@code ENFILE} will not resolve
 *       until another process closes file descriptors at the host level; bounded retry with
 *       circuit-breaker behaviour is the correct response.
 * </ul>
 *
 * Production failure mode: on a Kubernetes node hosting many containers each with high file
 * descriptor counts, the system-wide file table controlled by {@code fs.file-max} reaches capacity;
 * subsequent file-backed {@code mmap} calls from any container on the node fail with {@code
 * ENFILE}, causing cascading failures across services that cannot be resolved by the application
 * layer alone.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code ENFILE} when the system-wide limit on open file descriptions has been
 * reached. On Linux, this limit is controlled by {@code fs.file-max} (default approximately
 * 1,000,000 on modern systems) and checked in {@code alloc_empty_file}. When the total number of
 * open file descriptions across all processes reaches {@code fs.file-max}, the kernel returns
 * {@code -ENFILE} from any syscall that would allocate a new file description, including
 * file-backed {@code mmap} calls on some filesystem implementations.
 *
 * <p>The distinction between {@code ENFILE} and {@code EMFILE} is operationally critical: {@code
 * EMFILE} indicates that the current process has exhausted its {@code RLIMIT_NOFILE} quota and can
 * be fixed by calling {@code setrlimit(RLIMIT_NOFILE, ...)} or by closing unneeded file descriptors
 * within the process. {@code ENFILE} indicates that the kernel's global file description table is
 * full — no process can open new files until other processes release file descriptions. This
 * requires platform-team intervention to raise {@code fs.file-max} or to identify and remediate the
 * fd-leaking processes on the node.
 *
 * <p>In container environments, {@code ENFILE} typically appears when many containers each with
 * high {@code RLIMIT_NOFILE} limits (common in database and messaging workloads) are co-located on
 * a node. A single container's usage spike can push the node over the {@code fs.file-max}
 * threshold, causing {@code ENFILE} errors in completely unrelated containers. This cross-tenant
 * failure mode is invisible in single-container testing.
 *
 * <p>Compared with {@code EMFILE}: both prevent new file-backed mappings; the recovery paths are
 * completely different. An {@code EMFILE} runbook focuses on per-process fd eviction and limit
 * tuning; an {@code ENFILE} runbook focuses on node-level fd accounting and emergency platform
 * escalation. Conflating the two in application diagnostics leads to incorrect incident responses.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEnfile(probability = 0.001)
 * class SystemFdLimitTest {
 *   @Test
 *   void appHandlesEnfileOnFileMappings(RedisConnectionInfo info) {
 *     // verify structured error distinguishes ENFILE from EMFILE for correct escalation
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; system-wide exhaustion is rare in
 * isolated test environments — low probability is sufficient to exercise the error path and verify
 * diagnostic message quality.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.ENFILE)
public @interface ChaosMmapFileEnfile {

  /**
   * Probability that the fault fires when the rule matches, in the range {@code (0.0, 1.0]}. A
   * value of {@code 1.0} makes every matching call fail; {@code 0.001} fails one call in a
   * thousand. Values outside the range {@code (0.0, 1.0]} are rejected at rule construction time.
   */
  double probability() default 1.0;

  /**
   * Container id to bind this rule to. The value must match the {@code id} attribute of a container
   * annotation (e.g. {@code @RedisStandalone(id = "primary")}) on the same test class. The default
   * empty string {@code ""} applies the rule to every memory-chaos-capable container in the test
   * class. A non-empty id that does not match any declared container causes an {@code
   * ExtensionConfigurationException} at {@code beforeAll}.
   */
  String id() default "";

  /**
   * Policy applied when the active backend cannot honour the libchaos-memory requirement. {@link
   * OnMissingEnv#ERROR} (the default) fails the test class with an {@code
   * ExtensionConfigurationException} at {@code beforeAll}. {@link OnMissingEnv#ABORT} raises a
   * {@code TestAbortedException} instead, which most CI systems report as YELLOW (skipped/aborted)
   * rather than RED (failed), keeping the build clean in environments where libchaos is
   * unavailable.
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosMmapFileEnfile(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEnfile(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEnfile[] value();
  }
}
