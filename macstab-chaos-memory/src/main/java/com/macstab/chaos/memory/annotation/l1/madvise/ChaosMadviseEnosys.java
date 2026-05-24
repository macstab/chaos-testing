/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.madvise;

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
 * Injects {@code ENOSYS} into {@code madvise} calls intercepted by libchaos-memory, causing the
 * calling code to observe a function-not-implemented failure when providing a memory usage hint to
 * the kernel.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MADVISE}, errno = {@code ENOSYS}) tuple.
 * The {@code MADVISE} selector intercepts {@code madvise} calls only, leaving {@code mmap}, {@code
 * munmap}, and {@code mprotect} unaffected. Compile-time safety: invalid selector/errno
 * combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code madvise} wrapper at the dynamic-linker level.
 *   <li>On each {@code madvise} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENOSYS} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 38, {@code strerror}: "Function
 *       not implemented"; the hint is not applied.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code madvise} returns {@code -1}; {@code errno = ENOSYS} (38); the advice is not
 *       supported by the current kernel — the application should probe for support at startup,
 *       cache the result, and fall back to a compatible alternative.
 *   <li>Applications that use newer advice values (e.g. {@code MADV_FREE} Linux 4.5, {@code
 *       MADV_COLD} Linux 5.4) must handle {@code ENOSYS} gracefully when deployed on older kernels;
 *       assert that the application falls back to {@code MADV_DONTNEED} or accepts the performance
 *       degradation without crashing.
 *   <li>Assert that a one-time {@code ENOSYS} probe is performed at startup and the result is
 *       cached — avoid issuing the unsupported advice on every operation, as each attempt will
 *       incur a syscall overhead with no benefit.
 * </ul>
 *
 * Production failure mode: a container workload is deployed on a Kubernetes cluster whose nodes run
 * an older kernel version than the development environment; newer {@code madvise} advice values
 * used for JVM GC optimisation ({@code MADV_FREE}) or database memory management ({@code
 * MADV_PAGEOUT}) return {@code ENOSYS}, causing the JVM or engine to fall back to slower
 * memory-management strategies — an invisible performance regression at deployment time.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Unlike most other errnos, {@code ENOSYS} from {@code madvise} indicates that the entire
 * syscall or a specific advice value is not implemented by the running kernel, rather than an
 * argument error or a resource problem. The two main scenarios are: (1) the {@code madvise} syscall
 * itself is not present (extremely old kernels); (2) a specific advice value is not recognised — on
 * old kernels that predate the addition of the advice, the kernel returns {@code -EINVAL}, but on
 * kernels that explicitly gate new advice behind a compile-time option that was disabled, {@code
 * -ENOSYS} may be returned.
 *
 * <p>The standard pattern for handling {@code ENOSYS} from {@code madvise} is a one-time probe at
 * application startup: call {@code madvise} on a known-good region with the new advice value; if
 * the result is {@code -1} with {@code errno == ENOSYS} or {@code errno == EINVAL}, disable the new
 * advice path and use the fallback for the lifetime of the process. The JVM does this for {@code
 * MADV_FREE} (to avoid calling {@code madvise} on every GC cycle on old kernels). Databases that do
 * not probe at startup and instead receive {@code ENOSYS} on every operation saturate the syscall
 * path with failed calls.
 *
 * <p>This annotation simulates the {@code ENOSYS} response to verify that the probe-and-cache
 * pattern is correctly implemented: if the application probes once and disables the advice path,
 * only one {@code madvise} call will be intercepted; if the application does not probe and retries
 * on every operation, the annotation will fire repeatedly and the log will show repeated {@code
 * ENOSYS} errors — a clear indicator of a missing probe.
 *
 * <p>Compared with {@code EINVAL}: on older Linux kernels, unknown advice values return {@code
 * EINVAL}; on kernels that explicitly report unimplemented advice, {@code ENOSYS} may be returned.
 * Applications that probe for advice support should check for both errnos and treat both as "not
 * supported".
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseEnosys(probability = 0.5)
 * class MadviseProbeTest {
 *   @Test
 *   void appProbesAdviceSupportOnceAndCachesResult(RedisConnectionInfo info) {
 *     // verify only one madvise call is made per advice type; repeated ENOSYS indicates missing probe
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 0.5 to 1.0 for probe-detection tests (to ensure the
 * probe fires early); 1e-4 to 1e-3 for steady-state resilience tests.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMadviseEnosys.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MADVISE, errno = MmapErrno.ENOSYS)
public @interface ChaosMadviseEnosys {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container in the test class)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-memory ({@code ERROR} fails at
   *     {@code beforeAll}; {@code ABORT} marks the test class YELLOW/aborted)
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosMadviseEnosys(id = "primary",  probability = 0.001)
   * @ChaosMadviseEnosys(id = "replica",  probability = 0.01)
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
    ChaosMadviseEnosys[] value();
  }
}
