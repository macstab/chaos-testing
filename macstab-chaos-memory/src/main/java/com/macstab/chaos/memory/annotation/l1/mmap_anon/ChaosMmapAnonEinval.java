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
 * Injects {@code EINVAL} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the calling code to observe an invalid-argument failure from anonymous memory allocation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code EINVAL}) tuple.
 * Compile-time safety: this annotation exists only because {@code EINVAL} is a defined POSIX result
 * for {@code mmap}; invalid combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = EINVAL} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 22, {@code strerror}:
 *       "Invalid argument".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EINVAL} (22); the application
 *       should treat this as a programming error and surface a diagnostic.
 *   <li>glibc {@code malloc} propagates {@code NULL}; JVM direct allocators raise {@code
 *       OutOfMemoryError}. Native frameworks that pass explicit flags or alignment hints to {@code
 *       mmap} may produce richer error messages distinguishing {@code EINVAL}.
 *   <li>Assert that the application logs or reports the error rather than silently producing
 *       incorrect results from a null pointer.
 * </ul>
 *
 * Production failure mode: kernel version upgrades occasionally tighten argument validation in
 * {@code do_mmap}: a flag combination accepted on kernel 4.x may return {@code EINVAL} on kernel
 * 5.x or 6.x, silently breaking applications that never exercised this code path in CI.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EINVAL} for {@code mmap} when: the requested length is zero, the length
 * overflows the address-space limit, the protection flags are invalid, the combination of flags is
 * illegal (e.g. {@code MAP_PRIVATE | MAP_SHARED}), or the offset is not page-aligned. For anonymous
 * mappings the most common real-world trigger is a zero-length request, which glibc itself never
 * generates for normal {@code malloc} but which native extension code occasionally produces through
 * arithmetic underflow.
 *
 * <p>The Linux kernel validates arguments in {@code do_mmap_pgoff} before any resource allocation.
 * {@code EINVAL} is returned before any memory is reserved, so there is no partial state to clean
 * up. This makes it a "programmer error" errno — the caller must fix the arguments, not retry the
 * call. However, many allocator wrappers do not distinguish {@code EINVAL} from {@code ENOMEM} and
 * simply propagate a generic "allocation failed" error upward, hiding the root cause.
 *
 * <p>glibc's internal {@code mmap} call for anonymous allocations always uses a non-zero,
 * page-aligned length and valid flags, so normal {@code malloc} will never encounter {@code EINVAL}
 * from the kernel. However, custom allocators, JVM code-cache managers, off-heap libraries (Apache
 * Arrow, Chronicle Map), and JNA callers that compute sizes or offsets dynamically are all at risk
 * of producing an invalid argument under unusual input conditions.
 *
 * <p>Compared with siblings: {@code EINVAL} indicates a permanent argument error (no retry will
 * help without fixing the code); {@code ENOMEM} indicates resource exhaustion (retry after freeing
 * may help); {@code EAGAIN} indicates a transient condition (retry after a delay may help). This
 * distinct semantics makes it important to verify that application error-handling code preserves
 * the distinction rather than treating all {@code mmap} failures identically.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEinval(probability = 0.001)
 * class ArgumentValidationTest {
 *   @Test
 *   void appHandlesEinvalOnAlloc(RedisConnectionInfo info) {
 *     // drive allocations; assert a diagnostic error message, not a silent null dereference
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; {@code EINVAL} at high probability will
 * block all mapped I/O and crash the JVM or any process that relies on {@code mmap} for startup.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.EINVAL)
public @interface ChaosMmapAnonEinval {

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
   * @ChaosMmapAnonEinval(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEinval(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEinval[] value();
  }
}
