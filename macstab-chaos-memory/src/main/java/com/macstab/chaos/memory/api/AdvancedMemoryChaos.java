/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.api;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.MemoryChaos;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

/**
 * Capability-tier interface exposing libchaos-memory's VM-syscall fault-injection surface — the
 * sharp end that lets tests deterministically hit code paths almost no application ever exercises
 * in production-shaped CI.
 *
 * <p><strong>Pre-flight contract.</strong> Every method on this interface requires that the target
 * container has been prepared with libchaos-memory <em>before</em> {@code container.start()} — the
 * {@code .so} is hooked via {@code LD_PRELOAD}, which the dynamic loader only honours at process
 * launch. Without preparation, every advanced verb raises {@link LibchaosNotPreparedException}
 * loudly. Annotate the test class with {@code @SyscallLevelChaos(LibchaosLib.MEMORY)} to let {@code
 * ChaosTestingExtension} drive the preparation.
 *
 * <p><strong>Capability uplift over {@link MemoryChaos}.</strong> The portable parent interface
 * (cgroups-based {@code setLimit} / {@code setPressure} / {@code stress}) models whole-container
 * resource ceilings. This interface adds per-syscall granularity that cgroups cannot reach:
 *
 * <ul>
 *   <li><strong>Heap allocation failure</strong> — make a specific {@code malloc()} return {@code
 *       NULL} (musl: any size; glibc: ≥ 128 KiB). Stress-tests the {@code ENOMEM}-handling paths of
 *       your code that natural OOM cannot reach deterministically.
 *   <li><strong>Thread-creation failure</strong> — {@code pthread_create()} fails because
 *       allocating the thread stack ({@code mmap(MAP_ANONYMOUS)}) fails. The classic "we hit the
 *       process thread-cap and silently dropped requests" production-incident class.
 *   <li><strong>Library-load failure</strong> — {@code dlopen()} fails because mapping the ELF
 *       segments ({@code mmap(MAP_PRIVATE)} on the library fd) fails. Exercises plugin-loader error
 *       paths.
 *   <li><strong>JIT-compilation failure</strong> — {@code mprotect(PROT_EXEC)} returns {@code
 *       EACCES}. Useful for verifying that JVM/V8/PCRE2/etc. fall back to interpreted execution
 *       rather than crashing.
 *   <li><strong>Page-purge failure</strong> — {@code madvise(MADV_DONTNEED / MADV_FREE)} fails;
 *       useful for testing memory-reclaim code paths in allocators.
 *   <li><strong>Memory-unmap failure</strong> — {@code munmap()} fails; simulates leaks visible
 *       only in long-running processes.
 * </ul>
 *
 * <p><strong>Probability guidance.</strong> Many libchaos-memory rules need a low probability to
 * avoid breaking unrelated infrastructure (SSH daemon, package installer, language runtime
 * initialisation). The convenience verbs that accept a probability never default it — if you want
 * deterministic failure use {@code 1.0}, but for sustained chaos {@code 0.001}–{@code 0.01} is the
 * documented sweet spot.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * class MyTest {
 *   @Test
 *   void serverHandlesAllocationFailureGracefully(MemoryChaos chaos, GenericContainer<?> app) {
 *     AdvancedMemoryChaos adv = (AdvancedMemoryChaos) chaos;
 *
 *     // Make 0.1% of large allocations fail with ENOMEM
 *     RuleHandle h = adv.failHeapAllocation(app, 0.001);
 *
 *     // Drive load — server must remain responsive
 *     stressTheServer(app);
 *
 *     adv.remove(app, h);
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface AdvancedMemoryChaos extends MemoryChaos {

  // ==================== Generic rule API ====================

  /**
   * Apply a single libchaos-memory rule.
   *
   * @param container target container (must be prepared with libchaos-memory)
   * @param rule rule to apply
   * @return handle identifying the rule for later removal
   * @throws NullPointerException if any argument is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-memory is not active on {@code container}
   */
  RuleHandle apply(GenericContainer<?> container, MemoryRule rule);

  /**
   * Apply a batch of rules in a single round-trip. Validates every rule before committing any of
   * them (fail-fast).
   *
   * @return one handle per input rule, in the same order; empty when {@code rules} is empty
   * @throws NullPointerException if any argument or rule is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-memory is not active on {@code container}
   */
  List<RuleHandle> applyAll(GenericContainer<?> container, List<MemoryRule> rules);

  /**
   * Surgically remove a single previously-applied rule. Idempotent — silently no-op if the handle
   * is unknown to this strategy.
   */
  void remove(GenericContainer<?> container, RuleHandle handle);

  /** Remove every rule this strategy has applied to {@code container}. */
  void removeAll(GenericContainer<?> container);

  // ==================== Raw-rule escape hatches ====================

  /**
   * Apply an errno fault to an arbitrary selector — escape hatch when the typed convenience verbs
   * do not fit.
   *
   * @return handle for later removal
   * @throws IllegalArgumentException if {@code errno} is not valid for {@code selector}
   */
  RuleHandle errno(
      GenericContainer<?> container, MemorySelector selector, MmapErrno errno, double probability);

  /** Apply a latency effect to an arbitrary selector. */
  RuleHandle latency(GenericContainer<?> container, MemorySelector selector, Duration delay);

  // ==================== HEAP / ALLOCATION ====================

  /**
   * Inject {@code ENOMEM} on anonymous mmap calls — the canonical heap-failure rule.
   *
   * <ul>
   *   <li><strong>musl</strong>: every {@code malloc()} eventually goes through anonymous mmap
   *       (mallocng), so this affects allocations of every size
   *   <li><strong>glibc</strong>: only allocations ≥ {@code MMAP_THRESHOLD} (default 128 KiB) go
   *       through mmap — smaller allocations are arena-served and unaffected
   * </ul>
   *
   * @param container target container
   * @param probability probability in {@code (0.0, 1.0]} — keep low for sustained chaos ({@code
   *     0.001}–{@code 0.01}); use {@code 1.0} for deterministic single-shot tests
   * @return handle for later removal
   */
  RuleHandle failHeapAllocation(GenericContainer<?> container, double probability);

  /**
   * Inject {@code ENOMEM} on any mmap call — covers both anonymous and file-backed paths. Broader
   * than {@link #failHeapAllocation} but does not distinguish between malloc-induced and
   * dlopen-induced mmaps.
   */
  RuleHandle failLargeAllocation(GenericContainer<?> container, double probability);

  /**
   * Wildcard {@code ENOMEM} — fail every interposed VM syscall (mmap + mprotect + madvise) with the
   * same out-of-memory error. The closest libchaos-memory gets to simulating an OOM-killer regime
   * without actually invoking the kernel killer.
   *
   * @param probability use a moderate value ({@code 0.05}–{@code 0.2}); {@code 1.0} will almost
   *     certainly break the container itself
   * @return handle for later removal
   */
  RuleHandle simulateOomKiller(GenericContainer<?> container, double probability);

  /**
   * Inject {@code ENOMEM} on mmap calls at the given (typically low) rate — useful for long-running
   * soak tests where you want the app to occasionally observe allocation pressure.
   *
   * @param probability typically {@code 0.001}–{@code 0.01}
   */
  RuleHandle simulateMemoryPressure(GenericContainer<?> container, double probability);

  /**
   * Delay anonymous mmap calls — simulates a slow allocator / heavy contention without actually
   * failing the allocation.
   */
  RuleHandle slowHeapAllocation(GenericContainer<?> container, Duration delay);

  // ==================== FILE MAPPING / DLOPEN ====================

  /**
   * Inject {@code ENOMEM} on file-backed mmap calls — makes {@code dlopen()} and memory-mapped file
   * I/O fail.
   */
  RuleHandle failFileMapping(GenericContainer<?> container, double probability);

  /**
   * Inject an arbitrary errno on file-backed mmap calls — gives access to the full mmap errno
   * palette (EACCES, EBADF, ENFILE, ETXTBSY, …) for testing specific failure modes.
   *
   * @throws IllegalArgumentException if {@code errno} is not valid for mmap
   */
  RuleHandle failFileMapping(GenericContainer<?> container, MmapErrno errno, double probability);

  /**
   * Semantic alias of {@link #failFileMapping(GenericContainer, double)} targeting plugin-loader
   * test scenarios — {@code dlopen()} fails because the library's ELF segments cannot be mapped.
   */
  RuleHandle failLibraryLoad(GenericContainer<?> container, double probability);

  /**
   * Semantic alias of {@link #failFileMapping(GenericContainer, double)} for runtime plugin
   * loading.
   */
  RuleHandle failPluginLoad(GenericContainer<?> container, double probability);

  /** Delay file-backed mmap calls — simulates slow page-cache fills or slow storage. */
  RuleHandle slowFileMapping(GenericContainer<?> container, Duration delay);

  // ==================== THREAD / STACK ====================

  /**
   * Inject {@code ENOMEM} on anonymous mmap calls — since {@code pthread_create()} allocates each
   * thread's stack via anonymous mmap, this makes thread creation fail. Models the
   * thread-creation-failure incident class that production code rarely tests.
   */
  RuleHandle failThreadCreation(GenericContainer<?> container, double probability);

  /**
   * Inject {@code ENOMEM} on {@code mprotect()} — simulates failure of guard-page setup, which
   * {@code pthread_create()} performs to protect against stack overflow into adjacent mappings.
   */
  RuleHandle failGuardPageSetup(GenericContainer<?> container, double probability);

  // ==================== PAGE PERMISSION (mprotect) ====================

  /** Inject an arbitrary errno on {@code mprotect()}. */
  RuleHandle failPermissionChange(
      GenericContainer<?> container, MmapErrno errno, double probability);

  /**
   * Inject {@code EACCES} on {@code mprotect()} — fails JIT compilers' attempts to mark generated
   * code pages executable (V8, PCRE2 JIT, Hotspot's CodeCache, LuaJIT, …).
   *
   * <p>Useful for verifying that JIT-capable runtimes degrade gracefully to interpreted execution
   * rather than crashing.
   */
  RuleHandle failJitCompilation(GenericContainer<?> container, double probability);

  /** Delay {@code mprotect()} calls. */
  RuleHandle slowPermissionChange(GenericContainer<?> container, Duration delay);

  // ==================== KERNEL HINTS (madvise) ====================

  /** Inject an arbitrary errno on {@code madvise()}. */
  RuleHandle failMadvise(GenericContainer<?> container, MmapErrno errno, double probability);

  /**
   * Inject {@code EINVAL} on {@code madvise()} — exercises code paths that gracefully handle
   * unsupported hugepage hints ({@code MADV_HUGEPAGE} / {@code MADV_COLLAPSE}).
   */
  RuleHandle failHugepageHint(GenericContainer<?> container, double probability);

  /**
   * Inject {@code ENOMEM} on {@code madvise()} — fails {@code MADV_DONTNEED} / {@code MADV_FREE}
   * page-purge requests. Useful for testing allocators that rely on madvise for memory return to
   * the kernel.
   */
  RuleHandle failPagePurge(GenericContainer<?> container, double probability);

  /** Delay {@code madvise()} calls. */
  RuleHandle slowMadvise(GenericContainer<?> container, Duration delay);

  // ==================== CLEANUP ====================

  /**
   * Inject {@code EINVAL} on {@code munmap()} — the unmap succeeds from the application's
   * perspective only if it checks the return value (which most code does not). Simulates
   * memory-leak conditions that only surface in long-running processes.
   */
  RuleHandle failUnmap(GenericContainer<?> container, double probability);

  /** Semantic alias of {@link #failUnmap} — emphasises the leak-test intent. */
  RuleHandle simulateLeak(GenericContainer<?> container, double probability);
}
