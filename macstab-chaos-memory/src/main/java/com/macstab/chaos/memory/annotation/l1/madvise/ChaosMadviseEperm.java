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
 * Injects {@code EPERM} into {@code madvise} calls intercepted by libchaos-memory, causing the
 * calling code to observe an operation-not-permitted failure when providing a memory usage hint to
 * the kernel.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-memory primitive — one (selector = {@code MADVISE}, errno = {@code EPERM}) tuple. The
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
 *   <li>When the trial fires, the interposer sets {@code errno = EPERM} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 1, {@code strerror}: "Operation
 *       not permitted"; the hint is not applied.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code madvise} returns {@code -1}; {@code errno = EPERM} (1); the operation class is
 *       structurally disallowed for this process — retrying without a privilege change will not
 *       succeed.
 *   <li>Applications that use {@code madvise(MADV_HWPOISON)} for memory fault testing must only
 *       call this from privileged processes; assert that an {@code EPERM} response is handled
 *       gracefully and the test is skipped or reported as unsupported rather than causing a test
 *       assertion failure.
 *   <li>Assert that the application distinguishes {@code EPERM} from {@code EACCES} in its
 *       diagnostic — {@code EPERM} indicates the operation class is denied for this process
 *       (requires policy change); {@code EACCES} indicates credentials or LSM policy on a specific
 *       target (may be fixable with different credentials).
 * </ul>
 *
 * Production failure mode: a Kubernetes pod drops capabilities (e.g. removing all capabilities with
 * {@code securityContext.capabilities.drop: ALL}) that are required for privileged {@code madvise}
 * operations such as {@code MADV_HWPOISON}; the application's memory-fault testing subsystem
 * silently fails to inject faults, causing fault-injection tests to always pass (false positives)
 * rather than reporting an unsupported operation.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code EPERM} for {@code madvise} when the process does not have the required
 * privilege for the requested operation. On Linux, the primary case is {@code MADV_HWPOISON}: this
 * advice asks the kernel to simulate hardware memory poisoning of the specified pages — a
 * destructive operation that requires {@code CAP_SYS_ADMIN}. Without the capability, the kernel
 * returns {@code -EPERM} immediately without modifying any page state.
 *
 * <p>A second source of {@code EPERM} is the {@code MADV_GUARD_INSTALL} advice added in Linux 6.13
 * (experimental), which installs guard regions to detect buffer overflows; this operation may
 * require specific capabilities or seccomp-filter permissions on hardened deployments. Future
 * advice values that require privilege escalation will return {@code EPERM} on processes that lack
 * the required capabilities.
 *
 * <p>Unlike other {@code madvise} failures, {@code EPERM} for privileged operations is a permanent
 * denial for the current process class — no retry with the same arguments will succeed.
 * Applications that probe for capability availability at startup using {@code
 * madvise(MADV_HWPOISON)} must cache the {@code EPERM} result and skip the privileged
 * memory-fault-injection path for the lifetime of the process.
 *
 * <p>Compared with {@code EACCES}: {@code EPERM} is a capability check (the process class is not
 * permitted to issue this type of advice regardless of the target); {@code EACCES} is a
 * credentials/policy check against a specific target. Both are non-fatal for advisory hints; the
 * distinction is operationally significant for runbooks and for capability audits.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseEperm(probability = 1.0)
 * class PrivilegedAdviceCapabilityTest {
 *   @Test
 *   void appHandlesEpermOnMadviseHwpoisonGracefully(RedisConnectionInfo info) {
 *     // verify MADV_HWPOISON EPERM is cached and fault-injection path is correctly disabled
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1.0 for capability-probe tests (to always exercise the
 * EPERM path); 1e-3 to 1e-2 for steady-state resilience coverage.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMadviseEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MADVISE, errno = MmapErrno.EPERM)
public @interface ChaosMadviseEperm {

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
   * @ChaosMadviseEperm(id = "primary",  probability = 0.001)
   * @ChaosMadviseEperm(id = "replica",  probability = 0.01)
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
    ChaosMadviseEperm[] value();
  }
}
