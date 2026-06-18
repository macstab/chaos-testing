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
 * Injects {@code EBADF} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the calling code to observe a bad-file-descriptor failure from anonymous memory
 * allocation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code EBADF}) tuple.
 * Compile-time safety: this annotation exists only because {@code EBADF} is a defined POSIX result
 * for {@code mmap}; invalid combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
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
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EBADF} (9); the application should
 *       surface a clear diagnostic rather than attempting to use the returned pointer.
 *   <li>glibc {@code malloc} propagates the failure as {@code NULL}; JVM direct allocators raise
 *       {@code OutOfMemoryError}. Error messages may not distinguish {@code EBADF} from {@code
 *       ENOMEM} at the Java level — check native log lines or JMX metrics.
 *   <li>Assert that the application does not crash with a null dereference and that any
 *       file-descriptor lifecycle code correctly validates descriptors before use.
 * </ul>
 *
 * Production failure mode: a file-descriptor leak in a connection pool exhausts the process {@code
 * EMFILE} limit; when the pool attempts to open a new connection via a backing file and then maps
 * it, {@code EBADF} surfaces if the fd was silently closed by a race condition (e.g. {@code
 * close-on-exec} set incorrectly across a {@code fork}).
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EBADF} for {@code mmap} when the file descriptor argument is negative
 * or not open for reading (when {@code PROT_READ} is requested). For anonymous mappings ({@code
 * MAP_ANONYMOUS | MAP_PRIVATE}) the kernel ignores the {@code fd} argument entirely (callers pass
 * {@code -1} by convention), so genuine {@code EBADF} from the kernel on an anonymous mapping is
 * extremely unlikely in correct code. This annotation therefore exercises error-handling paths that
 * are almost never reached in production — making it especially valuable for verifying that
 * allocator error paths do not have dormant bugs.
 *
 * <p>The most common real-world scenario is a library that wraps a file-backed and an anonymous
 * path through a shared {@code mmap} function: if the caller passes a closed fd to the shared
 * function and then adds {@code MAP_ANONYMOUS} as an afterthought, the kernel may return {@code
 * EBADF} before it processes the {@code MAP_ANONYMOUS} flag, depending on the kernel version and
 * the order of argument validation in {@code do_mmap_pgoff}.
 *
 * <p>glibc's {@code malloc} does not call {@code mmap} with an explicit fd — it always uses {@code
 * -1} with {@code MAP_ANONYMOUS}. The {@code EBADF} path in {@code malloc}'s {@code mmap} branch is
 * therefore unreachable in practice, which means the error-recovery code in many libraries has
 * never been exercised. This annotation triggers it.
 *
 * <p>Compared with siblings: {@code EBADF} indicates an invalid descriptor (programmer error);
 * {@code EACCES} indicates a permissions check failure on a valid descriptor. Both surface as
 * allocation failure to libc callers but require different corrective action in native code.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEbadf(probability = 0.001)
 * class FdLifecycleTest {
 *   @Test
 *   void appHandlesEbadfOnAlloc(RedisConnectionInfo info) {
 *     // drive allocations; verify no null-pointer crash and correct error reporting
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> low rates (1e-4 to 1e-3) are sufficient to exercise the
 * error path; 1.0 prevents the container process from completing startup.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEbadf.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.EBADF)
public @interface ChaosMmapAnonEbadf {

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
   * @ChaosMmapAnonEbadf(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEbadf(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEbadf[] value();
  }
}
