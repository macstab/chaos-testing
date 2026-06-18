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
 * Injects {@code EAGAIN} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe a transient resource-unavailable failure
 * from any memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code EAGAIN}) tuple. The
 * {@code MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use {@code
 * ChaosMmapAnonEagain} or {@code ChaosMmapFileEagain} for narrower fault isolation. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = EAGAIN} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 11, {@code strerror}:
 *       "Resource temporarily unavailable".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EAGAIN} (11); callers should retry
 *       with back-off rather than treating the failure as permanent.
 *   <li>glibc {@code malloc} propagates {@code NULL} (it does not retry on {@code EAGAIN});
 *       file-mapping code should fall back to read/write I/O.
 *   <li>Assert that retry logic (where present) does not spin infinitely and that the application
 *       degrades gracefully under sustained transient pressure.
 * </ul>
 *
 * Production failure mode: cgroup memory controllers in soft-limit mode and kernels under severe
 * memory pressure can return {@code EAGAIN} for both anonymous and file-backed mappings during
 * page-reclaim storms, affecting all mapping call sites simultaneously.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX allows {@code mmap} to return {@code EAGAIN} when the mapping cannot be completed at
 * this moment but may succeed on retry. On Linux, this manifests in the cgroup memory controller
 * path when {@code try_charge} fails transiently, and in the {@code mmap_lock} contention path
 * under very high concurrency. For file-backed mappings, {@code EAGAIN} can also arise when the
 * filesystem's {@code mmap} implementation defers to a slow device path that is temporarily
 * blocked.
 *
 * <p>The broad {@code MMAP} selector simultaneously affects the heap allocator path (anonymous) and
 * the file I/O path (file-backed). Applications that use memory-mapped files for database storage
 * or log-segment management may see file I/O failures at the same time as allocation failures — a
 * combined stress scenario that is impossible to produce naturally.
 *
 * <p>Because {@code EAGAIN} semantically means "retry", applications that naively retry on every
 * {@code EAGAIN} from {@code mmap} will spin indefinitely under high probability values. This
 * annotation reveals whether retry budgets are bounded and whether back-off is implemented
 * correctly. Pair with a timeout assertion in the test to catch runaway retry loops.
 *
 * <p>Compared with {@code ENOMEM}: {@code EAGAIN} is transient (retry may succeed); {@code ENOMEM}
 * is permanent (retry after freeing memory may help). Both cause {@code MAP_FAILED} but the
 * application's response strategy should differ significantly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEagain(probability = 0.001)
 * class TransientMappingPressureTest {
 *   @Test
 *   void appHandlesEagainOnAllMmaps(RedisConnectionInfo info) {
 *     // verify retry logic is bounded and does not spin indefinitely
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3 simulates transient pressure; rates above
 * 0.01 exhaust retry budgets and produce cascading failures.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.EAGAIN)
public @interface ChaosMmapEagain {

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
   * @ChaosMmapEagain(id = "primary",  probability = 0.001)
   * @ChaosMmapEagain(id = "replica",  probability = 0.01)
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
    ChaosMmapEagain[] value();
  }
}
