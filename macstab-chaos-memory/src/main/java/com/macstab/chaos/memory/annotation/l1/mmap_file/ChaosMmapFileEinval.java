/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap_file;

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
 * Injects {@code EINVAL} into file-backed {@code mmap} calls intercepted by libchaos-memory,
 * causing the calling code to observe an invalid-argument failure when attempting to establish
 * a file-backed memory mapping.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code EINVAL})
 * tuple. The {@code MMAP_FILE} selector intercepts only file-backed {@code mmap} calls (those
 * without {@code MAP_ANONYMOUS}), leaving anonymous allocations unaffected. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each file-backed {@code mmap} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EINVAL} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 22,
 *       {@code strerror}: "Invalid argument".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EINVAL} (22); file-mapping code
 *       must treat the failure as a programming error in the mapping parameters and surface a
 *       diagnostic rather than retrying.</li>
 *   <li>Applications that compute the mapping offset from file metadata (size, block count) must
 *       handle arithmetic errors that produce zero-length or non-page-aligned values — assert that
 *       {@code EINVAL} is treated as fatal for the mapping attempt, not as a transient failure.</li>
 *   <li>Assert that the file descriptor is closed and any partially allocated resources are
 *       released before propagating the error — resource leaks after {@code EINVAL} are common.</li>
 * </ul>
 * Production failure mode: a kernel upgrade tightens the {@code mmap} argument validation rules
 * (for example, adding a stricter alignment check for hugepage-backed files), causing previously
 * successful mapping calls from applications that computed offsets without page-alignment
 * enforcement to begin returning {@code EINVAL} — a silent breaking change.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EINVAL} for file-backed {@code mmap} when: {@code length} is zero;
 * {@code flags} contains both {@code MAP_PRIVATE} and {@code MAP_SHARED} (or neither); {@code
 * addr} is not a multiple of the page size (when non-zero); or {@code offset} is not a multiple
 * of the page size. The kernel validates all four conditions in {@code do_mmap_pgoff} before
 * allocating the VMA.
 *
 * <p>For file-backed mappings, the offset validation is particularly significant: the offset must
 * be page-aligned because the kernel maps the file in page granularity. Applications that compute
 * the offset from file size, record counts, or block numbers must explicitly align down to the
 * page boundary with {@code offset & ~(PAGE_SIZE - 1)}. Arithmetic underflow or off-by-one in
 * this computation can produce a non-page-aligned value, causing {@code EINVAL} on the first
 * mapping that exercises the computed offset.
 *
 * <p>Kernel version differences in {@code EINVAL} triggering are significant: Linux 4.x added
 * stricter flag validation for {@code MAP_HUGETLB} with file-backed mappings, requiring the
 * file to have been opened with the hugepage filesystem flag. Applications that attempt to
 * map regular files with {@code MAP_HUGETLB} will receive {@code EINVAL} on kernel 4.x and
 * later but may have succeeded on earlier kernels. This class of regression is invisible in
 * development environments running older kernels.
 *
 * <p>Compared with {@code EBADF}: {@code EINVAL} is an argument-validity failure (the parameters
 * to the call are wrong); {@code EBADF} is a descriptor-validity failure (the fd itself is
 * wrong). Both are non-transient for the given arguments; recovery requires correcting the
 * mapping parameters rather than the fd. Unlike {@code EBADF}, {@code EINVAL} typically indicates
 * a programming error in the calling code rather than a runtime race.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEinval(probability = 0.001)
 * class MappingParameterValidationTest {
 *   @Test
 *   void appHandlesEinvalOnFileMappings(RedisConnectionInfo info) {
 *     // verify EINVAL is treated as fatal and resources are released cleanly
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2 to exercise the argument-validation
 * error path; rates above 0.1 will prevent shared-library loading and cause process abort.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.EINVAL)
public @interface ChaosMmapFileEinval {

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
   * @ChaosMmapFileEinval(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEinval(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEinval[] value();
  }
}
