/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.wildcard;

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
 * Injects {@code EINVAL} into every VM syscall ({@code mmap}, {@code munmap}, {@code mprotect},
 * {@code madvise}) intercepted by libchaos-memory, causing the calling code to observe an
 * invalid-argument failure across all memory-management operations simultaneously.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code WILDCARD}, errno = {@code EINVAL}) tuple.
 * The {@code WILDCARD} selector is the broadest available: it matches every syscall interposed by
 * libchaos-memory — {@code mmap} (anonymous and file-backed), {@code munmap}, {@code mprotect},
 * and {@code madvise} — with a single rule. Use narrower selectors ({@code MMAP_ANON},
 * {@code MMAP_FILE}, {@code MPROTECT}, {@code MUNMAP}, {@code MADVISE}) for targeted fault
 * isolation; use {@code WILDCARD} when you need to verify that a process handles comprehensive,
 * simultaneous VM argument validation failures across all memory-management paths.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc wrappers for {@code mmap}, {@code munmap}, {@code mprotect}, and
 *       {@code madvise} at the dynamic-linker level.</li>
 *   <li>On each intercepted call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EINVAL} and returns {@code -1}
 *       (or {@code MAP_FAILED} for {@code mmap}) without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} / {@code MAP_FAILED} return, {@code errno} 22,
 *       {@code strerror}: "Invalid argument"; no memory operation is performed.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>Every intercepted VM syscall returns {@code EINVAL} with the configured probability;
 *       the application must handle the failure on every memory-management path simultaneously
 *       rather than only the specific syscall under test.</li>
 *   <li>Applications that perform heap allocation ({@code mmap}), segment rotation ({@code munmap}),
 *       JIT code protection ({@code mprotect}), and readahead hints ({@code madvise}) in the same
 *       code path will encounter cascading {@code EINVAL} failures; assert that no single-path
 *       failure causes unhandled exceptions that mask failures on other paths.</li>
 *   <li>Assert that the application's error-handling aggregation (structured logging, metrics,
 *       error counters) correctly attributes each {@code EINVAL} to its originating call rather
 *       than conflating all VM failures under a single error category — the wildcard selector
 *       exercises all paths and exposes missing per-syscall error attribution.</li>
 * </ul>
 * Production failure mode: a kernel upgrade tightens argument validation (e.g. Linux 4.17 made
 * {@code mmap} reject negative {@code length} values explicitly; Linux 5.10 tightened
 * {@code mprotect} flags validation) causing previously-accepted calls to return {@code EINVAL};
 * this wildcard annotation simulates that environment-wide validation change for all VM syscalls
 * simultaneously, revealing which application paths lack correct error handling before deployment.
 *
 * <h2>Deep technical dive</h2>
 * <p>The {@code WILDCARD} selector is implemented by the libchaos-memory dispatcher as a catch-all
 * rule that is evaluated for every intercepted function call before selector-specific rules.
 * This means a wildcard rule with {@code probability = 0.001} has a 0.1% chance of firing on
 * every {@code mmap}, {@code munmap}, {@code mprotect}, and {@code madvise} call independently.
 * If the process makes 1000 VM syscalls per second — typical for a JVM during GC warmup — the
 * expected fault rate is 1 per second distributed across all four syscall types.
 *
 * <p>The per-syscall semantics of {@code EINVAL} differ significantly: for {@code mmap}, it
 * indicates bad flags, non-page-aligned offset, or invalid combination of {@code MAP_FIXED} and
 * misaligned {@code addr}; for {@code munmap} and {@code mprotect}, it indicates non-page-aligned
 * {@code addr} or arithmetic-overflowed range; for {@code madvise}, it indicates an unrecognised
 * advice value or non-page-aligned address. The application's error-handling code must be correct
 * for all four semantics simultaneously — wildcard injection reveals cases where only the most
 * common path (e.g. {@code mmap} failure) is handled and the others silently swallow errors.
 *
 * <p>The cascading effect of wildcard injection is the primary value of this selector: a process
 * that allocates memory with {@code mmap}, immediately calls {@code madvise(MADV_WILLNEED)} on
 * the region, and then calls {@code mprotect} to set execute permissions will encounter all three
 * failures in sequence. This exposes ordering dependencies in error handling: if the application
 * checks only the final {@code mprotect} return value and skips the intermediate steps, wildcard
 * injection will reveal the gap. JVM JIT compilers are particularly susceptible because they
 * allocate, advise, and protect code regions in a tight sequence during compilation.
 *
 * <p>Compared with per-syscall selectors: {@code MMAP_ANON}, {@code MMAP_FILE}, {@code MPROTECT},
 * {@code MUNMAP}, and {@code MADVISE} selectors inject faults only on their respective syscall,
 * enabling surgical fault isolation for targeted resilience testing. {@code WILDCARD} is the
 * broadest available scope — it is most useful for initial coverage sweeps (verifying that at
 * least some error handling exists on all paths) before moving to targeted selectors for specific
 * failure mode validation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosWildcardEinval(probability = 0.001)
 * class VmArgumentValidationTest {
 *   @Test
 *   void allVmSyscallsHandleEinvalWithoutUnhandledExceptions(RedisConnectionInfo info) {
 *     // verify no unhandled EINVAL from mmap, munmap, mprotect, or madvise during normal operation
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2 for initial coverage sweeps; values
 * above 0.1 will cause cascading failures across all VM paths simultaneously and may prevent the
 * process from starting (mmap during dynamic linking may fail). Values at 1.0 will crash the
 * process immediately during the dynamic linker's own memory allocations.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosWildcardEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.WILDCARD, errno = MmapErrno.EINVAL)
public @interface ChaosWildcardEinval {

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
   * @ChaosWildcardEinval(id = "primary",  probability = 0.001)
   * @ChaosWildcardEinval(id = "replica",  probability = 0.01)
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
    ChaosWildcardEinval[] value();
  }
}
