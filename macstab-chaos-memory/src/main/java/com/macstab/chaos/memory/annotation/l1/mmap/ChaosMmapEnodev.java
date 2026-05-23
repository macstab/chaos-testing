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
 * Injects {@code ENODEV} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe a no-such-device failure from any
 * memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code ENODEV}) tuple.
 * The {@code MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use
 * {@code ChaosMmapAnonEnodev} or {@code ChaosMmapFileEnodev} for narrower fault isolation.
 * Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = ENODEV} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 19,
 *       {@code strerror}: "No such device".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = ENODEV} (19); both heap
 *       allocations and file-mapping operations observe a device-failure errno.</li>
 *   <li>File-mapping code should distinguish {@code ENODEV} (hardware failure) from {@code ENOMEM}
 *       (resource limit) — assert that diagnostics name "No such device" rather than "Out of
 *       memory".</li>
 *   <li>Assert that neither the allocator path nor the file-I/O path silently returns corrupt data
 *       after receiving {@code ENODEV}.</li>
 * </ul>
 * Production failure mode: NUMA nodes, PMEM devices, or device-backed filesystems that are
 * hot-removed from a running host cause all subsequent {@code mmap} calls against affected
 * regions or files to fail with {@code ENODEV} — affecting both memory allocation and file I/O
 * simultaneously.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code ENODEV} for {@code mmap} when the filesystem underlying the mapped
 * file does not support memory mapping (the file system's {@code mmap} operation pointer is
 * {@code NULL}). This is most commonly seen with filesystems that explicitly disable memory
 * mapping (e.g. certain network filesystems in degraded state, or pseudo-filesystems like
 * {@code /proc} entries that have no {@code mmap} implementation).
 *
 * <p>The broad {@code MMAP} selector simultaneously affects anonymous allocations (heap path)
 * and file-backed mappings (I/O path). For file-backed mappings, {@code ENODEV} from a real
 * kernel indicates that the backing filesystem or device cannot provide the mapping — a
 * hardware or filesystem failure, not a resource limit. This is catastrophic for applications
 * like RocksDB or LMDB that use memory-mapped files as their primary I/O mechanism; a fallback
 * to read/write I/O must be implemented and tested.
 *
 * <p>For anonymous mappings, real kernel-level {@code ENODEV} is extremely rare because the
 * kernel's anonymous mapping path does not require a device. This annotation exercises the
 * error-recovery path for this unlikely scenario, which is often entirely untested.
 *
 * <p>Compared with siblings: {@code ENODEV} indicates the device or filesystem is absent or
 * incapable; {@code EACCES} indicates it is present but the credentials are wrong; {@code ENOMEM}
 * indicates it is present and accessible but full. All three require different incident responses.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEnodev(probability = 0.001)
 * class DeviceRemovalTest {
 *   @Test
 *   void appHandlesEnodevOnAllMmaps(RedisConnectionInfo info) {
 *     // verify hardware-failure error is surfaced and no data corruption occurs
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; device-removal failures are rare but
 * catastrophic when unhandled — low probability is sufficient to exercise the error path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEnodev.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.ENODEV)
public @interface ChaosMmapEnodev {

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
   * @ChaosMmapEnodev(id = "primary",  probability = 0.001)
   * @ChaosMmapEnodev(id = "replica",  probability = 0.01)
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
    ChaosMmapEnodev[] value();
  }
}
