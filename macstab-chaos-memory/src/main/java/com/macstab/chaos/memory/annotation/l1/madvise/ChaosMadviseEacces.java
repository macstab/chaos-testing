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
 * Injects {@code EACCES} into {@code madvise} calls intercepted by libchaos-memory, causing the
 * calling code to observe a permission-denied failure when providing a memory usage hint to the
 * kernel.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MADVISE}, errno = {@code EACCES})
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
 *   <li>When the trial fires, the interposer sets {@code errno = EACCES} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 13,
 *       {@code strerror}: "Permission denied"; the memory hint is not applied.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code madvise} returns {@code -1}; {@code errno = EACCES} (13); the application's
 *       memory layout hint is not applied — the kernel will manage the region with default
 *       policies, potentially causing higher TLB pressure or preventing Huge Page promotion.</li>
 *   <li>Applications that use {@code MADV_HUGEPAGE} to promote regions to Huge Pages for
 *       performance must handle {@code EACCES} gracefully — assert that the application falls
 *       back to base-page allocation without crashing and without silently degrading throughput
 *       without logging the permission denial.</li>
 *   <li>Assert that the application does not treat a failed {@code madvise} as fatal — the
 *       hint is advisory and its failure should surface as a degraded-performance warning,
 *       not as an error that prevents normal operation.</li>
 * </ul>
 * Production failure mode: a Kubernetes pod's cgroup or security policy prevents the process
 * from using {@code MADV_HUGEPAGE} or {@code MADV_DONTDUMP} on certain memory regions; the
 * JVM or database engine silently loses the performance benefit of huge pages without
 * logging the denial — causing unexplained latency regressions after a pod-policy change.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EACCES} for {@code madvise} when the process does not have
 * permission to use the requested advice on the given address range. On Linux, the primary
 * case is {@code MADV_HWPOISON}: only processes with {@code CAP_SYS_ADMIN} may issue this
 * advice, which tells the kernel to simulate hardware memory poisoning for testing. Without
 * the capability, the kernel returns {@code -EACCES}.
 *
 * <p>A second source of {@code EACCES} from {@code madvise} is an LSM hook: SELinux
 * {@code security_madvise} and AppArmor equivalents can deny specific advice operations on
 * specific regions based on security policy. A container running with a restrictive SELinux
 * profile may be denied the right to apply {@code MADV_MERGE} or {@code MADV_REMOVE} on
 * regions backed by certain file types, returning {@code EACCES}.
 *
 * <p>The critical correctness requirement for {@code madvise} errors is that the calling code
 * must treat the advice as optional. POSIX states that {@code madvise} provides "advice", not
 * a mandatory operation; the kernel is always free to ignore it. Code that checks the return
 * value of {@code madvise} and treats a non-zero result as fatal violates the advisory
 * semantics of the syscall. This annotation tests for this violation: if the application
 * aborts or throws on {@code EACCES}, the test fails.
 *
 * <p>Compared with {@code EPERM}: {@code EACCES} is a credentials/policy check against a
 * specific target object or operation class; {@code EPERM} is a structural check that the
 * process class is not permitted to issue this type of advice regardless of the target.
 * In practice, LSM policies return {@code EACCES} for most {@code madvise} denials on Linux.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseEacces(probability = 0.001)
 * class HugepageAdviceTest {
 *   @Test
 *   void appHandlesEaccesOnMadviseGracefully(RedisConnectionInfo info) {
 *     // verify application logs the denial and continues without fatal error
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; madvise failures should never be
 * fatal, so any probability is safe from a correctness standpoint — test at a rate that
 * exercises the code path without flooding logs.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMadviseEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MADVISE, errno = MmapErrno.EACCES)
public @interface ChaosMadviseEacces {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container in the test class)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-memory ({@code ERROR} fails at
   *     {@code beforeAll}; {@code ABORT} marks the test class YELLOW/aborted)
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosMadviseEacces(id = "primary",  probability = 0.001)
   * @ChaosMadviseEacces(id = "replica",  probability = 0.01)
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
    ChaosMadviseEacces[] value();
  }
}
