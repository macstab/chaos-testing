/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap;

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
 * Injects {@code ENFILE} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe a system-wide file-descriptor limit failure
 * from any memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code ENFILE}) tuple. The
 * {@code MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use {@code
 * ChaosMmapAnonEnfile} or {@code ChaosMmapFileEnfile} for narrower fault isolation. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENFILE} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 23, {@code strerror}:
 *       "Too many open files in system".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = ENFILE} (23); both heap allocations
 *       and file-mapping operations are affected simultaneously.
 *   <li>Applications should preserve the distinction between {@code ENFILE} (host-level, requires
 *       infrastructure intervention) and {@code EMFILE} (process-level, fixable by closing unused
 *       descriptors).
 *   <li>Assert that error logs contain the specific errno value or "Too many open files in system"
 *       rather than a generic "Out of memory" or "I/O error" message.
 * </ul>
 *
 * Production failure mode: a Kubernetes node hosting many containers can exhaust the system-wide
 * file-descriptor table ({@code fs.file-max}); all processes on the node then receive {@code
 * ENFILE} from any fd-consuming syscall, including file-backed {@code mmap} calls inside running
 * containers — a host-level incident that looks identical to OOM from the application's perspective
 * unless the errno is preserved in logs.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code ENFILE} when the system-wide limit on open files ({@code fs.file-max})
 * is reached. The kernel's global file-table structure ({@code files_struct}) is allocated from a
 * slab cache that can be exhausted under high-concurrency workloads running many containers. On
 * Linux, {@code ENFILE} is generated in {@code alloc_empty_file} when the global file count would
 * exceed {@code sysctl fs.file-max}.
 *
 * <p>For the broad {@code MMAP} selector, both anonymous and file-backed paths are injected
 * simultaneously. This represents a host-level failure that affects all processes on the system.
 * Applications running in containers should be able to recognise and log this condition distinctly
 * from per-process {@code EMFILE} failures.
 *
 * <p>The most important difference from {@code EMFILE}: {@code ENFILE} cannot be remediated by the
 * process itself — it requires the host operator to either increase {@code fs.file-max}, reduce
 * container density, or restart the most fd-heavy processes. Applications that log {@code ENFILE}
 * as "Out of memory" or conflate it with {@code EMFILE} make incident response significantly
 * harder.
 *
 * <p>Compared with {@code EMFILE}: {@code EMFILE} is per-process (one process hit its own {@code
 * RLIMIT_NOFILE}); {@code ENFILE} is system-wide (all processes on the host are affected). Both
 * produce identical {@code MAP_FAILED} returns from {@code mmap}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEnfile(probability = 0.001)
 * class SystemFdExhaustionTest {
 *   @Test
 *   void appDistinguishesEnfileFromEnomem(RedisConnectionInfo info) {
 *     // verify logs contain "Too many open files in system" rather than "Out of memory"
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; system-wide limit exhaustion is rare in
 * single-host environments — low probability is sufficient to exercise the error-handling branch.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.ENFILE)
public @interface ChaosMmapEnfile {

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
   * @ChaosMmapEnfile(id = "primary",  probability = 0.001)
   * @ChaosMmapEnfile(id = "replica",  probability = 0.01)
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
    ChaosMmapEnfile[] value();
  }
}
