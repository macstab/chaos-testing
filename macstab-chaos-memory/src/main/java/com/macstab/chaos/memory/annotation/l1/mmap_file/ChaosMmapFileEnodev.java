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
 * Injects {@code ENODEV} into file-backed {@code mmap} calls intercepted by libchaos-memory,
 * causing the calling code to observe a no-such-device failure when mapping a file into memory.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code ENODEV}) tuple.
 * The {@code MMAP_FILE} selector intercepts file-backed {@code mmap} calls only (those where {@code
 * MAP_ANONYMOUS} is absent and a valid file descriptor is passed), leaving anonymous allocations,
 * {@code munmap}, {@code mprotect}, and {@code madvise} unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each file-backed {@code mmap} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENODEV} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 19, {@code strerror}:
 *       "No such device"; the file is not mapped into the address space.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = ENODEV} (19); the filesystem
 *       backing the file does not support memory mapping — the application must fall back to {@code
 *       pread}/{@code pwrite} I/O or report the file as inaccessible for mmap.
 *   <li>Storage engines (RocksDB, LMDB, LevelDB) that use file-backed {@code mmap} as their primary
 *       I/O path must handle {@code ENODEV} on SST file or data file mapping attempts gracefully —
 *       assert that the engine falls back to {@code pread}-based I/O rather than crashing or
 *       returning a corrupted view of the data.
 *   <li>Assert that the application does not silently use a partially-mapped view — if the
 *       application maps multiple files and only one returns {@code ENODEV}, it must detect the
 *       failure before accessing the region; accessing an unmapped region causes {@code SIGSEGV},
 *       not a graceful error.
 * </ul>
 *
 * Production failure mode: a network-attached filesystem (NFS, CIFS, GlusterFS) serving database
 * files enters a degraded mode during a network partition — some operations succeed while {@code
 * mmap} operations return {@code ENODEV} because the filesystem driver's {@code f_op->mmap} pointer
 * is cleared when the transport drops; the database's mmap-based read path silently returns {@code
 * MAP_FAILED} and the engine crashes at the first access to the presumed-mapped region.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel returns {@code ENODEV} from {@code mmap} when the filesystem backing the file does
 * not implement the {@code mmap} operation — specifically, when the file's {@code
 * file_operations.mmap} function pointer is {@code NULL}. This is the correct return value for
 * filesystems that are architecturally incapable of supporting memory-mapped I/O: FUSE filesystems
 * that do not set the {@code FUSE_CAP_MMAP_READ} capability, certain pseudo-filesystems ({@code
 * proc}, {@code sysfs} entries that are not specially implemented), and network filesystems in
 * degraded states where the transport layer cannot provide the page-cache coherency guarantees that
 * memory mapping requires.
 *
 * <p>The most operationally significant source of {@code ENODEV} from {@code mmap} on Linux is
 * network filesystem degradation: NFS and CIFS implementations set their {@code mmap} function
 * pointer conditionally based on mount options and connection state. When the filesystem driver
 * detects a transport failure and clears its operational state, subsequent {@code mmap} calls
 * against the mounted filesystem return {@code ENODEV} rather than the more commonly expected
 * {@code EIO}. This distinction is operationally significant: {@code EIO} indicates an I/O error on
 * a present device; {@code ENODEV} indicates the device or driver is absent entirely. Recovery from
 * {@code EIO} may involve retry; recovery from {@code ENODEV} requires the filesystem to be
 * remounted or a different I/O path (such as {@code pread}) to be used.
 *
 * <p>FUSE-based filesystems are a second important source: user-space FUSE drivers that do not
 * implement the {@code mmap} callback will have their kernel-side {@code f_op->mmap} set to {@code
 * NULL}. This is common for FUSE drivers that provide a read-write interface but not a
 * page-cache-coherent view — for example, encryption FUSE drivers (gocryptfs, EncFS) that must
 * decrypt data at access time cannot provide the zero-copy page-cache access that {@code mmap}
 * requires, and therefore legitimately return {@code ENODEV}. Applications that deploy on systems
 * using FUSE encryption layers must handle {@code ENODEV} from {@code mmap} gracefully.
 *
 * <p>Compared with {@code EACCES}: {@code ENODEV} indicates the filesystem or driver does not
 * support memory mapping at all (structural absence of the operation); {@code EACCES} indicates the
 * filesystem supports mapping but the file's permissions or protection flags do not permit the
 * requested access (credentials issue, fixable without changing filesystem). Compared with {@code
 * ENOMEM}: {@code ENODEV} indicates the device/filesystem cannot serve the request; {@code ENOMEM}
 * indicates the kernel cannot allocate the page-table resources to represent the mapping. Both are
 * mapping failures, but only {@code ENOMEM} is potentially transient.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEnodev(probability = 0.001)
 * class MmapFilesystemCapabilityTest {
 *   @Test
 *   void engineFallsBackToPreadWhenMmapReturnsEnodev(RedisConnectionInfo info) {
 *     // verify storage engine uses pread fallback; assert no SIGSEGV on mmap failure
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; mmap failures at file-open time are
 * infrequent in production but the fallback path is rarely exercised in development — any non-zero
 * probability usefully exercises the pread fallback path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEnodev.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.ENODEV)
public @interface ChaosMmapFileEnodev {

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
   * @ChaosMmapFileEnodev(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEnodev(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEnodev[] value();
  }
}
