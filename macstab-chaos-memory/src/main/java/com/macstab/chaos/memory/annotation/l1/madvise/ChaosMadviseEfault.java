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
 * Injects {@code EFAULT} into {@code madvise} calls intercepted by libchaos-memory, causing the
 * calling code to observe a bad-address failure when providing a memory usage hint to the kernel.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MADVISE}, errno = {@code EFAULT})
 * tuple. The {@code MADVISE} selector intercepts {@code madvise} calls only, leaving
 * {@code mmap}, {@code munmap}, and {@code mprotect} unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code madvise} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code madvise} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EFAULT} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 14,
 *       {@code strerror}: "Bad address"; the hint is not applied.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code madvise} returns {@code -1}; {@code errno = EFAULT} (14); the hint is not applied
 *       — the application must not assume the advised behaviour is active and must not depend on
 *       the hint having taken effect for correctness.</li>
 *   <li>Memory managers that advise address ranges computed from allocation metadata must
 *       validate page alignment before issuing {@code madvise}; assert that {@code EFAULT}
 *       triggers a diagnostic that includes the address and length so that stale-pointer bugs
 *       can be attributed from logs.</li>
 *   <li>Assert that a failed {@code madvise} is treated as a best-effort hint failure — the
 *       application must not abort, retry indefinitely, or degrade correctness in response
 *       to a failed hint.</li>
 * </ul>
 * Production failure mode: a native memory manager advises address ranges for performance
 * optimisation (e.g. {@code MADV_WILLNEED} for a segment it is about to access); a
 * concurrent {@code munmap} in another thread removes the mapping before the {@code madvise}
 * call completes, causing the kernel to return {@code EFAULT} for the now-unmapped range —
 * a race that is invisible in single-threaded testing.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EFAULT} for {@code madvise} when the address range
 * {@code [addr, addr+len)} extends outside the process's accessible address space. On Linux,
 * the kernel walks the VMA list in {@code do_madvise} and returns {@code -EFAULT} if it
 * encounters a gap in the VMA tree within the requested range — meaning part of the range
 * is not mapped. This is distinct from {@code EINVAL} (which is returned for misaligned
 * addresses or invalid advice values).
 *
 * <p>The most common production path to {@code EFAULT} from {@code madvise} is a TOCTOU race:
 * a thread computes an address range (which is valid at that moment) and then passes it to
 * {@code madvise} after another thread has unmapped the range. Between the computation and
 * the syscall, the VMA is gone. The kernel sees a gap in the VMA tree and returns
 * {@code EFAULT}. This race is difficult to reproduce without fault injection.
 *
 * <p>A second path is incorrect range arithmetic: a memory manager that tracks region sizes
 * in 32-bit integers and promotes them to 64-bit without sign-extension can produce
 * {@code addr + len} that exceeds the process's virtual address space, causing
 * {@code EFAULT} from the overflow check. This is the same integer-width bug that can
 * affect {@code munmap} (see {@code ChaosMunmapEfault}).
 *
 * <p>Compared with {@code EINVAL}: {@code EFAULT} occurs when the address range contains an
 * inaccessible gap or overflows the address space (structural mapping problem); {@code EINVAL}
 * occurs when the arguments are structurally invalid regardless of the current mapping state
 * (misaligned address, zero length, unknown advice). Both are non-fatal for advisory hints.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseEfault(probability = 0.0001)
 * class MadviseRaceTest {
 *   @Test
 *   void appHandlesEfaultOnMadviseWithoutFatalError(RedisConnectionInfo info) {
 *     // verify TOCTOU EFAULT is logged and the application proceeds without the hint
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> very low (1e-5 to 1e-4) to simulate the TOCTOU
 * race; higher rates are safe since madvise failures are non-fatal.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMadviseEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MADVISE, errno = MmapErrno.EFAULT)
public @interface ChaosMadviseEfault {

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
   * @ChaosMadviseEfault(id = "primary",  probability = 0.001)
   * @ChaosMadviseEfault(id = "replica",  probability = 0.01)
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
    ChaosMadviseEfault[] value();
  }
}
