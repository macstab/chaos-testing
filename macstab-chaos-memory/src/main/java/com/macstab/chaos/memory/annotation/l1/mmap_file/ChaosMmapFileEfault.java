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
 * Injects {@code EFAULT} into file-backed {@code mmap} calls intercepted by libchaos-memory,
 * causing the calling code to observe a bad-address failure when attempting to establish a
 * file-backed memory mapping.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code EFAULT}) tuple.
 * The {@code MMAP_FILE} selector intercepts only file-backed {@code mmap} calls (those without
 * {@code MAP_ANONYMOUS}), leaving anonymous allocations unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each file-backed {@code mmap} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = EFAULT} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 14, {@code strerror}:
 *       "Bad address".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EFAULT} (14); the caller must not
 *       dereference the return value — treating {@code MAP_FAILED} as a valid address causes an
 *       immediate {@code SIGSEGV}.
 *   <li>Assert that the application checks the return value against {@code MAP_FAILED} before
 *       accessing any byte of the mapped region, and that it surfaces a structured error rather
 *       than dereferencing the sentinel value.
 *   <li>Assert that the application's error message includes the errno string "Bad address" so that
 *       operators can distinguish this from {@code EBADF} or {@code EACCES}.
 * </ul>
 *
 * Production failure mode: a stale hint address cached by a native allocator, JNA binding, or
 * memory-mapped I/O subsystem causes a file-backed {@code mmap} call to pass an address range that
 * was unmapped by a concurrent {@code munmap} — the kernel returns {@code EFAULT} on the hint
 * validation before establishing the mapping.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EFAULT} for {@code mmap} when the {@code addr} hint falls within an
 * inaccessible address range. On Linux, the kernel's {@code do_mmap_pgoff} rejects hint addresses
 * below {@code vm.mmap_min_addr} (typically 65536) and addresses that overlap with inaccessible
 * kernel-reserved regions. For file-backed mappings, the kernel also validates that the requested
 * address range does not conflict with existing VMAs in a way that would require splitting a sealed
 * mapping.
 *
 * <p>In practice, real {@code EFAULT} from file-backed {@code mmap} on a normally configured Linux
 * system is extremely rare because the kernel ignores hint addresses that conflict with existing
 * mappings (returning a different address) unless {@code MAP_FIXED} is set. With {@code MAP_FIXED},
 * a stale hint that falls within a kernel-reserved range or below {@code mmap_min_addr} produces
 * {@code EFAULT}. Database engines that use {@code MAP_FIXED} for deterministic layout (e.g.
 * large-address-space databases that need contiguous address ranges) are most exposed.
 *
 * <p>The JVM rarely uses file-backed {@code MAP_FIXED} mappings for user data — it prefers
 * anonymous mappings for the heap. However, GraalVM native images and JVM internal infrastructure
 * (code cache placement on some platforms) may use hint-based placement with narrow address ranges.
 * A stale hint caused by arena reuse or address-space layout change can produce {@code EFAULT} on
 * the file path in these environments.
 *
 * <p>Compared with {@code EINVAL}: {@code EFAULT} occurs when the address is inaccessible to the
 * kernel (protection-level issue); {@code EINVAL} occurs when the arguments are structurally
 * invalid (zero length, bad flags). Both prevent mapping establishment and are non-transient;
 * neither requires resource release beyond discarding the stale address.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEfault(probability = 0.00005)
 * class StaleHintAddressTest {
 *   @Test
 *   void appHandlesEfaultOnFileMappings(RedisConnectionInfo info) {
 *     // verify MAP_FAILED check is present and no SIGSEGV follows
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> very low (1e-5 to 1e-4); {@code EFAULT} at high rates
 * prevents all file-backed mapping operations, breaking shared-library loading and causing process
 * abort rather than structured error handling.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.EFAULT)
public @interface ChaosMmapFileEfault {

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
   * @ChaosMmapFileEfault(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEfault(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEfault[] value();
  }
}
