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
 * Injects {@code EAGAIN} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the calling code to observe a transient resource-unavailable failure on anonymous
 * memory allocation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code EAGAIN}) tuple.
 * Compile-time safety: this annotation exists only because {@code EAGAIN} is a defined POSIX result
 * for {@code mmap}; invalid combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EAGAIN} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives the transient-failure signal: {@code MAP_FAILED} return,
 *       {@code errno} 11, {@code strerror}: "Resource temporarily unavailable".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EAGAIN} (11); well-written code
 *       should retry with back-off or report a transient error rather than crashing.</li>
 *   <li>glibc {@code malloc} does not retry on {@code EAGAIN} from {@code mmap} — it propagates
 *       {@code NULL}; the JVM raises {@code OutOfMemoryError} for the affected allocation.</li>
 *   <li>Assert that the application tolerates transient allocation failures without data
 *       corruption, and that retry logic (where present) does not loop infinitely.</li>
 * </ul>
 * Production failure mode: kernels under severe memory pressure combined with {@code RLIMIT_AS}
 * limits, or a cgroup memory controller near its high-watermark, can return {@code EAGAIN}
 * instead of {@code ENOMEM} for anonymous mappings — a transient condition that catches callers
 * who treat all allocation failures as fatal.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX allows {@code mmap} to return {@code EAGAIN} when the kernel has insufficient resources
 * to complete the request but the condition is transient. On Linux this arises when
 * {@code mmap_sem} (now {@code mmap_lock}) cannot be acquired in write mode within the kernel's
 * internal retry budget, or when a memory cgroup is operating in soft-limit mode and the page
 * reclaim loop yields {@code -ENOMEM} through the {@code EAGAIN} propagation path in specific
 * kernel versions (3.x/4.x era). Under normal conditions the kernel prefers {@code ENOMEM} for
 * permanent exhaustion and avoids {@code EAGAIN} for anonymous mappings; its occurrence in
 * production is therefore rare but not impossible.
 *
 * <p>Because {@code EAGAIN} semantically means "try again", well-designed allocators may loop
 * when they receive it. This creates a subtle risk: if the probability is high, the retry loop
 * spins without ever succeeding, consuming CPU and causing watchdog timeouts. Design retry
 * budgets carefully and pair this annotation with a timeout assertion.
 *
 * <p>For JVM workloads using {@code DirectByteBuffer} or JNA, the distinction between {@code
 * EAGAIN} and {@code ENOMEM} is invisible — both produce {@code OutOfMemoryError}. The difference
 * matters only in native C/C++ allocators and in frameworks that wrap {@code mmap} directly
 * (e.g. RocksDB's memory table allocator, MemSQL's native storage engine).
 *
 * <p>Compared with siblings: {@code ENOMEM} signals structural exhaustion (retry is futile);
 * {@code EAGAIN} signals transience (retry may succeed). {@code EINVAL} signals a programmer
 * error. Test both {@code EAGAIN} and {@code ENOMEM} to verify the application handles each
 * code path correctly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEagain(probability = 0.001)
 * class TransientAllocationTest {
 *   @Test
 *   void appHandlesEagainOnAlloc(RedisConnectionInfo info) {
 *     // drive allocations; assert the application retries or returns a graceful error
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3 simulates transient pressure; rates
 * above 0.01 exhaust retry budgets quickly and produce cascading failures.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.EAGAIN)
public @interface ChaosMmapAnonEagain {

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
   * @ChaosMmapAnonEagain(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEagain(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEagain[] value();
  }
}
