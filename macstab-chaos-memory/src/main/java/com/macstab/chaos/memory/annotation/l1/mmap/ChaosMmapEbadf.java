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
 * Injects {@code EBADF} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe a bad-file-descriptor failure from any
 * memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code EBADF}) tuple.
 * The {@code MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use
 * {@code ChaosMmapAnonEbadf} or {@code ChaosMmapFileEbadf} for narrower fault isolation.
 * Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EBADF} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 9,
 *       {@code strerror}: "Bad file descriptor".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EBADF} (9); both the anonymous
 *       allocator path and the file-backed mapping path observe the failure simultaneously.</li>
 *   <li>File-mapping code that passes a real fd to {@code mmap} should surface a diagnostic
 *       naming the invalid descriptor; allocator code should propagate {@code NULL} or
 *       {@code OutOfMemoryError}.</li>
 *   <li>Assert that no code path silently proceeds with a {@code MAP_FAILED} pointer.</li>
 * </ul>
 * Production failure mode: a shared file descriptor closed by one thread while another thread
 * attempts to map it ({@code close-on-exec} race, or an erroneous {@code dup2} overwriting a
 * live fd) produces real {@code EBADF} from the file-backed {@code mmap} — an error that is
 * nearly impossible to reproduce in testing without fault injection.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EBADF} for {@code mmap} when the file descriptor is not a valid open
 * file descriptor, or when the fd was opened in write-only mode ({@code O_WRONLY}) and
 * {@code PROT_READ} is requested. For anonymous mappings ({@code MAP_ANONYMOUS}), the kernel
 * ignores the fd (conventionally {@code -1}) and cannot return {@code EBADF} from that path.
 *
 * <p>The broad {@code MMAP} selector injects {@code EBADF} on both call paths. This is realistic
 * for applications that share a code path for both anonymous and file-backed mappings (e.g. an
 * allocator that tries a file-backed mapping first and falls back to anonymous). Both paths
 * must guard against {@code EBADF} even if only one is expected to receive it in practice.
 *
 * <p>The most impactful scenario is file-backed mappings in database storage engines. RocksDB,
 * LMDB, and similar engines keep file descriptors open for extended periods; if a monitoring
 * process or a buggy cleanup routine closes one of these descriptors, the next {@code mmap}
 * call against it returns {@code EBADF}. Error recovery typically requires reopening the file,
 * but this may race with concurrent writes — a complex failure mode that this annotation
 * exercises without any production risk.
 *
 * <p>Compared with {@code EACCES}: {@code EBADF} means the descriptor is invalid (closed or
 * never opened); {@code EACCES} means the descriptor is valid but the permissions are wrong.
 * Both are non-transient programmer errors but require different corrective action.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEbadf(probability = 0.001)
 * class FdRaceConditionTest {
 *   @Test
 *   void appHandlesEbadfOnAllMmaps(RedisConnectionInfo info) {
 *     // verify no code path dereferences MAP_FAILED and that diagnostics name the bad fd
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> low rates (1e-4 to 1e-3) are sufficient to exercise
 * the error path; 1.0 prevents the process from loading shared libraries.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEbadf.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.EBADF)
public @interface ChaosMmapEbadf {

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
   * @ChaosMmapEbadf(id = "primary",  probability = 0.001)
   * @ChaosMmapEbadf(id = "replica",  probability = 0.01)
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
    ChaosMmapEbadf[] value();
  }
}
