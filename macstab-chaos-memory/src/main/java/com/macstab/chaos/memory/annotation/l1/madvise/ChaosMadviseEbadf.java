/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.madvise;

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
 * Injects {@code EBADF} into {@code madvise} calls intercepted by libchaos-memory, causing the
 * calling code to observe a bad-file-descriptor failure when providing a memory usage hint to the
 * kernel.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MADVISE}, errno = {@code EBADF}) tuple. The
 * {@code MADVISE} selector intercepts {@code madvise} calls only, leaving {@code mmap}, {@code
 * munmap}, and {@code mprotect} unaffected. Compile-time safety: invalid selector/errno
 * combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code madvise} wrapper at the dynamic-linker level.
 *   <li>On each {@code madvise} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = EBADF} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 9, {@code strerror}: "Bad file
 *       descriptor"; the hint is not applied.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code madvise} returns {@code -1}; {@code errno = EBADF} (9); the hint is not applied —
 *       the kernel will manage the region with default policies.
 *   <li>The standard POSIX {@code madvise} signature takes an address range, not a file descriptor;
 *       real {@code EBADF} from {@code madvise} is effectively unreachable on standard Linux. This
 *       annotation exercises the dormant error path for the case where an application wraps {@code
 *       madvise} and checks the return value — assert that the wrapper propagates the errno
 *       correctly and does not silently swallow it.
 *   <li>Assert that the application does not treat a failed {@code madvise} as fatal — assert that
 *       it logs the errno and continues without the hint benefit.
 * </ul>
 *
 * Production failure mode: some kernel patches and custom syscall wrappers extend {@code madvise}
 * to accept a file descriptor parameter for file-range advice; on these non-standard kernels a
 * stale or invalid fd returns {@code EBADF}, causing advisors that assume success to lose the
 * performance benefit of the hint without logging a diagnostic.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The standard Linux {@code madvise(2)} syscall takes {@code (void *addr, size_t length, int
 * advice)} — there is no file descriptor parameter, so {@code EBADF} cannot arise from the kernel's
 * standard argument validation. The {@code process_madvise(2)} syscall introduced in Linux 5.10
 * adds a PID file descriptor ({@code pidfd}) parameter; if the {@code pidfd} refers to a closed or
 * invalid file description, the kernel returns {@code -EBADF}.
 *
 * <p>Applications that use {@code process_madvise} (cross-process memory hints, used by Android's
 * LMKD and some memory-management daemons) must handle {@code EBADF} from the {@code pidfd}
 * validation before the advice is applied. A {@code pidfd} that becomes invalid due to process exit
 * or file-descriptor recycling between the hint call and the kernel validation produces {@code
 * EBADF} atomically — the hint is not applied and no partial state is created.
 *
 * <p>For applications that use the standard single-process {@code madvise}, this annotation
 * exercises the generic {@code madvise} error-handling code path that is typically only reachable
 * for {@code EINVAL} and {@code EACCES}. Ensuring the error handler correctly processes {@code
 * EBADF} (not just the errnos it was tested against) verifies defensive error handling.
 *
 * <p>Compared with {@code EINVAL}: {@code EBADF} indicates a descriptor validity failure (wrong fd,
 * applicable to {@code process_madvise}); {@code EINVAL} indicates argument validity failure (wrong
 * address, length, or advice flag). Both should be treated as non-fatal advisory failures; neither
 * requires retrying with the same arguments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseEbadf(probability = 0.001)
 * class MadviseErrnoHandlingTest {
 *   @Test
 *   void appHandlesEbadfOnMadviseWithoutFatalError(RedisConnectionInfo info) {
 *     // verify EBADF is logged and the application continues without the hint
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; madvise failures are non-fatal, so any
 * probability is safe from a correctness standpoint — use a rate that exercises the error path
 * without flooding logs.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMadviseEbadf.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MADVISE, errno = MmapErrno.EBADF)
public @interface ChaosMadviseEbadf {

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
   * @ChaosMadviseEbadf(id = "primary",  probability = 0.001)
   * @ChaosMadviseEbadf(id = "replica",  probability = 0.01)
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
    ChaosMadviseEbadf[] value();
  }
}
