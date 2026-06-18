/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap_anon;

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
 * Injects {@code ENFILE} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the calling code to observe a system-wide file-descriptor limit failure from anonymous
 * memory allocation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code ENFILE}) tuple.
 * Compile-time safety: this annotation exists only because {@code ENFILE} is a defined POSIX result
 * for {@code mmap}; invalid combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
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
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = ENFILE} (23); callers should
 *       surface a resource-limit error distinct from OOM — many do not.
 *   <li>glibc {@code malloc} propagates {@code NULL}; JVM allocators raise {@code
 *       OutOfMemoryError}, losing the distinction between host-level fd exhaustion and heap
 *       pressure.
 *   <li>Assert that the application's error-handling code produces a diagnostic that distinguishes
 *       "too many open files in system" from "out of memory" — this distinction matters for
 *       operations runbooks.
 * </ul>
 *
 * Production failure mode: a Kubernetes node running many containers simultaneously can exhaust the
 * system-wide file descriptor table ({@code fs.file-max}); every process on the node then receives
 * {@code ENFILE} from any fd-creating syscall, causing all applications to fail in ways that look
 * identical to OOM unless error codes are preserved.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code ENFILE} when the system-wide limit on open files ({@code fs.file-max}
 * on Linux, default ~1 million) is reached. Unlike {@code EMFILE} (per-process), {@code ENFILE}
 * affects all processes on the host simultaneously. On Linux the global file table is a kernel data
 * structure that grows dynamically up to {@code fs.file-max}; exhausting it is rare on modern hosts
 * but becomes reachable under container-dense deployments on shared nodes.
 *
 * <p>Like {@code EMFILE}, the kernel does not check {@code fs.file-max} for pure anonymous mappings
 * ({@code MAP_ANONYMOUS}) because no file descriptor is allocated. This annotation therefore
 * intentionally exercises an error code that the kernel would not naturally return for this
 * specific syscall, targeting the correctness of error-handling code that must deal with the code
 * when it arrives from other, co-occurring syscalls on the same thread.
 *
 * <p>The distinction between {@code EMFILE} and {@code ENFILE} is important for incident response:
 * {@code EMFILE} is correctable by the process itself (close unused fds, shrink connection pools);
 * {@code ENFILE} requires host-level intervention (increase {@code fs.file-max}, reduce container
 * density, or restart the most fd-heavy processes). Applications that do not log the specific errno
 * value leave operators guessing.
 *
 * <p>Compared with {@code EMFILE}: same observable effect (allocation failure) but different scope
 * and remediation. Both should be handled in application error-recovery code; this annotation
 * exercises the {@code ENFILE} branch specifically.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEnfile(probability = 0.001)
 * class SystemFdLimitTest {
 *   @Test
 *   void appHandlesEnfileOnAlloc(RedisConnectionInfo info) {
 *     // verify the application logs "Too many open files in system" rather than "Out of memory"
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; system-wide limit exhaustion is rare in
 * production — low probability is sufficient to exercise the error-handling branch.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.ENFILE)
public @interface ChaosMmapAnonEnfile {

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
   * @ChaosMmapAnonEnfile(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEnfile(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEnfile[] value();
  }
}
