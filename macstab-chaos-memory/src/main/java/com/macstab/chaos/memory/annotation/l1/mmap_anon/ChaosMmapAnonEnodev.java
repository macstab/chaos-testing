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
 * Injects {@code ENODEV} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the calling code to observe a no-such-device failure from anonymous memory allocation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code ENODEV}) tuple.
 * Compile-time safety: this annotation exists only because {@code ENODEV} is a defined POSIX result
 * for {@code mmap}; invalid combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENODEV} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 19, {@code strerror}:
 *       "No such device".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = ENODEV} (19); callers should treat
 *       this as a non-transient infrastructure failure and escalate the error.
 *   <li>glibc {@code malloc} propagates {@code NULL}; JVM allocators raise {@code
 *       OutOfMemoryError}. Error messages will typically not distinguish {@code ENODEV} from {@code
 *       ENOMEM} at the Java level; native log lines and crash dumps are more informative.
 *   <li>Assert that the application does not silently corrupt state and that it surfaces a
 *       hardware-level error diagnostic that distinguishes device failure from OOM.
 * </ul>
 *
 * Production failure mode: NUMA nodes being hot-removed from a running system, persistent memory
 * (PMEM/NVDIMM) devices being unplugged, or software RAID arrays going offline can cause the memory
 * subsystem to return {@code ENODEV} for mappings that reference those devices.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code ENODEV} for {@code mmap} when the filesystem underlying the mapped file
 * does not support memory mapping (the file system's {@code f_op->mmap} is {@code NULL}). For
 * anonymous mappings, this error is produced by the kernel when a backing device (such as a {@code
 * /dev/mem} region, a PMEM device, or a DAX-capable block device) becomes unavailable after the
 * process started but before the mapping is established.
 *
 * <p>This scenario is most relevant for applications that map device memory directly (e.g.
 * SPDK-based storage engines, DPDK packet buffers, or GPU memory via {@code /dev/nvidia}) and for
 * HPC applications that use PMEM-backed anonymous mappings through the {@code MEMKIND_DAX}
 * allocator family. In container environments it can arise when a host-side device is hot-removed
 * while a container that has volume-mounted the device is still running.
 *
 * <p>Standard glibc {@code malloc} uses {@code /dev/zero} (or {@code MAP_ANONYMOUS}) and never
 * touches hardware-backed devices, so it never encounters {@code ENODEV} in practice. This errno is
 * therefore relevant only for native code that maps device memory explicitly. The JVM is unaffected
 * unless it is configured with off-heap allocators that target PMEM (e.g. Memkind or the JVM's own
 * {@code -XX:AllocateHeapAt} option targeting a DAX filesystem).
 *
 * <p>Compared with siblings: {@code ENODEV} indicates a device-level infrastructure failure
 * (hardware gone); {@code EACCES} indicates a permissions failure (device present but access
 * denied); {@code ENOMEM} indicates a capacity limit. All three cause {@code MAP_FAILED} but
 * require very different incident responses.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEnodev(probability = 0.001)
 * class DeviceFailureTest {
 *   @Test
 *   void appHandlesEnodevOnAlloc(RedisConnectionInfo info) {
 *     // verify the application surfaces a device-failure error rather than a generic OOM
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; device-removal failures are rare but
 * catastrophic — low probability is sufficient to exercise the error-handling branch.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEnodev.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.ENODEV)
public @interface ChaosMmapAnonEnodev {

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
   * @ChaosMmapAnonEnodev(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEnodev(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEnodev[] value();
  }
}
