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
 * Injects {@code EBADF} into file-backed {@code mmap} calls intercepted by libchaos-memory, causing
 * the calling code to observe a bad-file-descriptor failure when attempting to establish a
 * file-backed memory mapping.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code EBADF}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = EBADF} and returns {@code
 *       MAP_FAILED} without issuing the real kernel call.
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 9, {@code strerror}:
 *       "Bad file descriptor".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EBADF} (9); the application must
 *       treat the mapped region as unavailable and either re-open the file or surface a structured
 *       error.
 *   <li>Database engines (RocksDB, LMDB, HaloDB) that hold long-lived file descriptors for SST or
 *       data files may receive {@code EBADF} if another thread closes or replaces the fd via {@code
 *       dup2}; assert that no data corruption results from the aborted mapping.
 *   <li>Assert that the application does not indefinitely retry with the same (now invalid)
 *       descriptor — each retry will also fail and may cause a busy-loop.
 * </ul>
 *
 * Production failure mode: a close-on-exec race, a {@code dup2} overwrite in a concurrent thread,
 * or a file descriptor accidentally closed by a third-party library causes all subsequent {@code
 * mmap} calls on that fd to fail with {@code EBADF} — a class of bugs that is essentially
 * untestable without fault injection.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EBADF} for file-backed {@code mmap} when {@code fildes} is not a valid
 * open file descriptor or when the file was not opened with a mode that allows the requested
 * mapping protection. The kernel validates the fd in {@code do_mmap_pgoff} before allocating the
 * VMA: if the fd does not reference an open {@code struct file}, the kernel returns {@code -EBADF}
 * immediately.
 *
 * <p>The most common production scenario is a fd lifecycle race: one thread opens a file and passes
 * the fd to a memory-mapping subsystem; concurrently, another thread (or a signal handler) closes
 * or replaces the fd before the mapping is established. The {@code close(2)} call does not block on
 * outstanding {@code mmap} setup — once the fd is closed, any subsequent {@code mmap} with that fd
 * number will either fail with {@code EBADF} or (worse) map the file opened by a racing {@code
 * open(2)} call that was assigned the same fd number.
 *
 * <p>A subtler case arises with O_WRONLY files: POSIX requires that if {@code PROT_READ} is
 * requested and the fd was opened write-only, the kernel returns {@code EBADF} (not {@code
 * EACCES}). This distinction is important: {@code EBADF} means "wrong fd usage", whereas {@code
 * EACCES} means "credential check failed". Applications that open files with {@code O_RDWR} for
 * performance and later downgrade the open flags via {@code fcntl(F_SETFL)} may encounter this
 * scenario if the downgrade races with a mapping attempt.
 *
 * <p>Compared with {@code EACCES}: {@code EBADF} is a structural fd-validity failure (the
 * descriptor itself is wrong); {@code EACCES} is a credentials check failure on a valid descriptor.
 * Both are non-transient for the given fd; recovery requires obtaining a fresh descriptor with the
 * correct flags. The recovery path is different: {@code EBADF} requires re-open and fd repair;
 * {@code EACCES} may require permission escalation or filesystem remount.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEbadf(probability = 0.001)
 * class FdLifecycleTest {
 *   @Test
 *   void appHandlesEbadfOnFileMappings(RedisConnectionInfo info) {
 *     // verify the application re-opens the file and does not corrupt data
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3 is sufficient to exercise fd-lifecycle
 * guards; rates above 0.01 will prevent the process from establishing any file-backed mapping,
 * breaking shared-library loading.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEbadf.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.EBADF)
public @interface ChaosMmapFileEbadf {

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
   * @ChaosMmapFileEbadf(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEbadf(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEbadf[] value();
  }
}
