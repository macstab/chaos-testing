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
 * Injects {@code ENOMEM} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the allocator to observe virtual-address-space or RAM+swap exhaustion on demand.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code ENOMEM}) tuple.
 * The combination is enforced at compile time: this annotation class exists only because {@code
 * ENOMEM} is a valid POSIX result of {@code mmap(MAP_ANONYMOUS)}; invalid selector/errno pairings
 * have no annotation class and therefore cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENOMEM} and returns {@code
 *       MAP_FAILED} ({@code (void*)-1}) without issuing the real kernel call.
 *   <li>The calling code receives the same value it would from a real out-of-memory condition:
 *       {@code malloc} and friends return {@code NULL}, {@code new} throws {@code std::bad_alloc},
 *       and JVM direct allocators surface {@code OutOfMemoryError}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>glibc {@code malloc} returns {@code NULL} for requests above {@code MMAP_THRESHOLD} (~128
 *       KB by default) and sets {@code errno = ENOMEM} ({@code strerror}: "Out of memory").
 *   <li>C++ {@code new} throws {@code std::bad_alloc}; Java {@code ByteBuffer.allocateDirect} and
 *       JNA native allocations throw {@code OutOfMemoryError: Direct buffer memory}.
 *   <li>Application logs, metrics, or error responses should contain OOM-related messages or error
 *       codes — assert these rather than relying on return values alone.
 * </ul>
 *
 * Production failure mode: a container reaching its cgroup memory limit causes every large
 * anonymous allocation to return {@code ENOMEM}, silently corrupting data structures unless the
 * caller checks the return value.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies that {@code mmap} shall return {@code MAP_FAILED} and set {@code errno} to
 * {@code ENOMEM} when the kernel cannot allocate the requested address space. For anonymous
 * mappings this occurs when the process virtual-address space is exhausted (32-bit address space
 * limits, {@code vm.max_map_count} exceeded) or when RAM plus swap is insufficient to back the
 * mapping under strict overcommit ({@code vm.overcommit_memory=2}).
 *
 * <p>The Linux kernel raises {@code ENOMEM} in {@code do_mmap} when the VMA count reaches {@code
 * sysctl_max_map_count} (default 65 536), when the requested region overlaps an existing mapping
 * and merging fails due to a fragmented VMA tree, or when the cgroup memory controller rejects the
 * charge. Under {@code vm.overcommit_memory=0} (heuristic, the default) the kernel rarely raises
 * {@code ENOMEM} for anonymous mappings but does so at very high allocation rates on
 * memory-constrained machines.
 *
 * <p>glibc wraps {@code mmap} with a thin veneer that translates the kernel {@code -ENOMEM} return
 * into a C {@code errno = ENOMEM} / return-{@code MAP_FAILED} pair; {@code malloc} internally calls
 * {@code mmap} for allocations above {@code MMAP_THRESHOLD} and propagates {@code NULL} to the
 * caller. The JVM uses anonymous mappings for code cache, metaspace, and {@code DirectByteBuffer} —
 * all three paths raise {@code OutOfMemoryError} when {@code mmap} fails. JNA and Panama allocators
 * share the same code path. Application frameworks that pool native buffers (Netty, gRPC, Kafka
 * clients) may attempt retries but will exhaust their budgets quickly if the probability is too
 * high.
 *
 * <p>Compared with sibling errnos: {@code EAGAIN} is transient (retry may succeed), whereas {@code
 * ENOMEM} indicates structural exhaustion — retrying without releasing memory is futile. {@code
 * EINVAL} indicates a programmer error (wrong flags or alignment); {@code ENOMEM} indicates a
 * resource limit. Test both to verify that the application handles each case distinctly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEnomem(probability = 0.001)
 * class MemoryFaultTest {
 *   @Test
 *   void appHandlesEnomemOnAlloc(RedisConnectionInfo info) {
 *     // drive allocations; assert the application returns a graceful error response
 *     // rather than a NullPointerException or crash
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3 mirrors realistic OOM rates in production;
 * values above 0.1 will typically prevent the container process from completing startup, making the
 * test fail at infrastructure level.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class. Use the repeatable form to bind different probabilities to different containers
 * simultaneously.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.ENOMEM)
public @interface ChaosMmapAnonEnomem {

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
   * @ChaosMmapAnonEnomem(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEnomem(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEnomem[] value();
  }
}
