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
 * Injects {@code EINVAL} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe an invalid-argument failure from any
 * memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code EINVAL}) tuple. The
 * {@code MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use {@code
 * ChaosMmapAnonEinval} or {@code ChaosMmapFileEinval} for narrower fault isolation. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = EINVAL} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 22, {@code strerror}:
 *       "Invalid argument".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EINVAL} (22); both the anonymous
 *       allocator path and the file-backed mapping path observe invalid-argument failures.
 *   <li>glibc {@code malloc} propagates {@code NULL}; file-mapping code should surface a diagnostic
 *       naming the invalid parameter — assert this is logged rather than silently swallowed.
 *   <li>Assert that the application does not retry indefinitely — {@code EINVAL} is a permanent
 *       argument error, not a transient condition.
 * </ul>
 *
 * Production failure mode: kernel upgrades occasionally tighten argument validation in {@code
 * do_mmap}; a flag combination accepted on kernel 4.x may return {@code EINVAL} on kernel 6.x,
 * silently breaking applications that never exercised this code path in CI.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EINVAL} for {@code mmap} when any argument is invalid: zero length,
 * non-page-aligned offset or length, invalid flag combination ({@code MAP_PRIVATE | MAP_SHARED}),
 * or invalid protection flags. For file-backed mappings, additional validation (offset must be
 * non-negative and page-aligned) applies.
 *
 * <p>The broad {@code MMAP} selector simultaneously injects {@code EINVAL} on both call paths. This
 * is more aggressive than the anonymous-only or file-only selectors and stresses the application's
 * argument-validation error-handling on all {@code mmap} call sites simultaneously. Applications
 * that use {@code mmap} for both heap allocations and file I/O (e.g. database engines) must handle
 * {@code EINVAL} gracefully in both subsystems.
 *
 * <p>The Linux kernel validates {@code mmap} arguments in {@code do_mmap_pgoff} before any resource
 * allocation. Returning {@code EINVAL} leaves no partial state to clean up. However, if the
 * application has already allocated resources in preparation for the mapping (e.g. opened a file,
 * computed an address range), those resources must be released in the error-handling path — a
 * common source of resource leaks.
 *
 * <p>glibc's {@code malloc} never generates invalid {@code mmap} arguments, so the anonymous path
 * will never produce real {@code EINVAL} under normal operation. File-mapping code that computes
 * offsets or lengths dynamically is at risk when input validation is insufficient — a common
 * pattern in codecs, serialisation libraries, and database storage engines.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEinval(probability = 0.001)
 * class ArgumentValidationTest {
 *   @Test
 *   void appHandlesEinvalOnAllMmaps(RedisConnectionInfo info) {
 *     // verify that both allocator and file-mapping paths produce diagnostics, not crashes
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; high probability will block all mapped
 * I/O and crash the JVM or any process that relies on {@code mmap} for shared-library loading.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.EINVAL)
public @interface ChaosMmapEinval {

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
   * @ChaosMmapEinval(id = "primary",  probability = 0.001)
   * @ChaosMmapEinval(id = "replica",  probability = 0.01)
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
    ChaosMmapEinval[] value();
  }
}
