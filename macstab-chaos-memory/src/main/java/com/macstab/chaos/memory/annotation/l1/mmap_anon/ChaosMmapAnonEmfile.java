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
 * Injects {@code EMFILE} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the calling code to observe a per-process file-descriptor limit failure from anonymous
 * memory allocation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code EMFILE}) tuple.
 * Compile-time safety: this annotation exists only because {@code EMFILE} is a defined POSIX result
 * for {@code mmap}; invalid combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EMFILE} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 24,
 *       {@code strerror}: "Too many open files".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EMFILE} (24); the application
 *       should report a resource-limit error rather than dereferencing the failed pointer.</li>
 *   <li>glibc {@code malloc} propagates {@code NULL}; JVM allocators raise {@code OutOfMemoryError}.
 *       Applications that close fds in response to {@code EMFILE} from other syscalls should
 *       exercise the same logic here.</li>
 *   <li>Assert that connection pools, file-descriptor trackers, or resource monitors surface the
 *       per-process limit condition and that the application degrades gracefully.</li>
 * </ul>
 * Production failure mode: long-lived processes with slow file-descriptor leaks (connections not
 * closed, temporary files not unlinked) eventually hit {@code RLIMIT_NOFILE}; every subsequent
 * {@code open}, {@code socket}, or internally {@code mmap} call fails with {@code EMFILE}.
 * Applications that conflate {@code EMFILE} with {@code ENOMEM} may misdiagnose the incident as
 * an OOM event rather than a descriptor leak.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EMFILE} when the per-process limit on the number of open file
 * descriptors ({@code RLIMIT_NOFILE}) would be exceeded by the {@code mmap} call. On Linux,
 * anonymous {@code mmap} does not allocate a file descriptor (unlike file-backed mappings), so
 * the kernel does not check {@code RLIMIT_NOFILE} for {@code MAP_ANONYMOUS} calls. In practice,
 * {@code EMFILE} from a real anonymous {@code mmap} would indicate a kernel bug.
 *
 * <p>However, this annotation intentionally injects {@code EMFILE} to verify that application
 * error-handling code handles it correctly when it appears. Because many allocator wrappers treat
 * all {@code mmap} failure codes uniformly, applications that do not test this code path may
 * crash, loop, or produce corrupt output when the real {@code EMFILE} condition arises from a
 * concurrent {@code open} or {@code socket} call on a different thread, which races with the
 * {@code malloc} call.
 *
 * <p>For file-backed mmaps ({@code MMAP} selector), {@code EMFILE} is a realistic kernel response
 * when the file descriptor used in the {@code mmap} call was itself the last descriptor and the
 * mapping internally creates an additional reference. For anonymous mappings it serves as a
 * canary for the error-handling robustness of the allocation stack.
 *
 * <p>Compared with sibling {@code ENFILE}: {@code EMFILE} is per-process (one process hit its
 * own limit); {@code ENFILE} is system-wide (the entire host has exhausted the global fd table).
 * Both produce allocation failures but require different remediation in operations runbooks.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEmfile(probability = 0.001)
 * class FdLimitTest {
 *   @Test
 *   void appHandlesEmfileOnAlloc(RedisConnectionInfo info) {
 *     // drive allocations; verify the application reports a resource-limit error
 *     // rather than a null-pointer crash
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2 to simulate fd-limit pressure; combine
 * with a reduced {@code RLIMIT_NOFILE} in the container for maximum realism.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.EMFILE)
public @interface ChaosMmapAnonEmfile {

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
   * @ChaosMmapAnonEmfile(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEmfile(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEmfile[] value();
  }
}
