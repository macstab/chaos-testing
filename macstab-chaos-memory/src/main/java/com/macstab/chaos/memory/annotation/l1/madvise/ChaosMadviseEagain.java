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
 * Injects {@code EAGAIN} into {@code madvise} calls intercepted by libchaos-memory, causing the
 * calling code to observe a transient-failure response when providing a memory usage hint to
 * the kernel.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MADVISE}, errno = {@code EAGAIN})
 * tuple. The {@code MADVISE} selector intercepts {@code madvise} calls only, leaving
 * {@code mmap}, {@code munmap}, and {@code mprotect} unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code madvise} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code madvise} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EAGAIN} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 11,
 *       {@code strerror}: "Resource temporarily unavailable"; the hint is not applied.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code madvise} returns {@code -1}; {@code errno = EAGAIN} (11); the hint is not
 *       applied — the kernel will manage the region with default policies until the advice
 *       is successfully reissued or the mapping is released.</li>
 *   <li>Applications that call {@code madvise(MADV_WILLNEED)} to pre-fault pages before a
 *       latency-sensitive read operation must handle {@code EAGAIN} gracefully — assert that
 *       the application does not fail the read and instead proceeds without the pre-fault
 *       benefit.</li>
 *   <li>Assert that the application does not retry {@code madvise} in a tight loop on
 *       {@code EAGAIN} — the correct response is to treat the hint as best-effort and proceed,
 *       or to retry with exponential back-off if the hint is genuinely required.</li>
 * </ul>
 * Production failure mode: under memory pressure, the kernel's {@code madvise(MADV_FREE)}
 * operation (used by glibc to return pages to the OS without unmapping) can temporarily fail
 * with {@code EAGAIN} when the kernel's lazy-free accounting structures are contended; callers
 * that retry in a tight loop saturate the CPU on a code path that should be fire-and-forget.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EAGAIN} from {@code madvise} is rare on Linux — most advice operations are
 * synchronous and either succeed or fail with a definitive error. The primary case where
 * {@code EAGAIN} may appear is {@code MADV_POPULATE_READ} and {@code MADV_POPULATE_WRITE}
 * (added in Linux 5.14), which ask the kernel to fault in pages; if the kernel cannot
 * allocate memory to service the page faults, it returns {@code EAGAIN} to indicate the
 * caller should retry later.
 *
 * <p>For older advice values ({@code MADV_WILLNEED}, {@code MADV_DONTNEED}, {@code MADV_FREE}),
 * the kernel rarely returns {@code EAGAIN} because these operations are asynchronous hints
 * with no guarantee of immediate execution. This annotation exercises the error-handling path
 * for the {@code MADV_POPULATE_*} case and for future advice operations that may return
 * {@code EAGAIN} under pressure.
 *
 * <p>The practical correctness requirement is that {@code madvise} results must never be
 * treated as mandatory. Since {@code madvise} provides advisory hints, a failure of any kind
 * — including {@code EAGAIN} — must result in the application proceeding without the hint
 * benefit, not in an error that propagates to the caller or that causes a retry loop. JVM
 * implementations that use {@code madvise} for GC hint management (e.g. {@code MADV_FREE}
 * for returning G1 heap regions to the OS) should be resilient to {@code EAGAIN}.
 *
 * <p>Compared with {@code EINVAL}: {@code EAGAIN} indicates a transient resource contention
 * (retry may succeed); {@code EINVAL} indicates a structural argument error (retry with the
 * same arguments will not succeed). Both should be treated as non-fatal for advisory hints,
 * but the recovery strategy differs — {@code EAGAIN} warrants a retry with back-off;
 * {@code EINVAL} warrants logging and proceeding without the hint.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseEagain(probability = 0.001)
 * class MadviseRetryTest {
 *   @Test
 *   void appHandlesEagainOnMadviseWithoutTightLoop(RedisConnectionInfo info) {
 *     // verify EAGAIN does not cause retry loop and read completes without the pre-fault
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; madvise failures are non-fatal, so
 * any probability is safe from a correctness standpoint — test at a rate that exercises the
 * retry/fallback code path without flooding logs.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMadviseEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MADVISE, errno = MmapErrno.EAGAIN)
public @interface ChaosMadviseEagain {

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
   * @ChaosMadviseEagain(id = "primary",  probability = 0.001)
   * @ChaosMadviseEagain(id = "replica",  probability = 0.01)
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
    ChaosMadviseEagain[] value();
  }
}
