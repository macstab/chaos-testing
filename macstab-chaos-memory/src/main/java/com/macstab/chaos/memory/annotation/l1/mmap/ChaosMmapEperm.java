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
 * Injects {@code EPERM} into all {@code mmap} calls (anonymous and file-backed) intercepted by
 * libchaos-memory, causing the calling code to observe an operation-not-permitted failure from
 * any memory-mapping operation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP}, errno = {@code EPERM}) tuple.
 * The {@code MMAP} selector covers both anonymous and file-backed {@code mmap} calls; use
 * {@code ChaosMmapAnonEperm} or {@code ChaosMmapFileEperm} for narrower fault isolation.
 * Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EPERM} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 1,
 *       {@code strerror}: "Operation not permitted".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EPERM} (1); both the anonymous
 *       allocator path and the file-backed mapping path are affected simultaneously.</li>
 *   <li>JVM code-cache expansion (which requires {@code PROT_EXEC}) is particularly at risk;
 *       the JVM may fall back to interpreted mode or abort.</li>
 *   <li>Assert that the application surfaces a privilege-related diagnostic and does not
 *       retry indefinitely — {@code EPERM} is permanent without a privilege change.</li>
 * </ul>
 * Production failure mode: a tightened seccomp profile or Kubernetes Pod Security Admission
 * change applied to a new container revision can simultaneously deny {@code PROT_EXEC} on
 * anonymous mappings (JVM code cache) and prevent write-shared mappings on files (memory-mapped
 * databases), causing cascading failures across both subsystems.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EPERM} for {@code mmap} when the operation is structurally
 * disallowed regardless of credentials. On Linux this arises when: {@code PROT_EXEC} is
 * requested on a noexec filesystem or when the Yama LSM blocks executable anonymous mappings;
 * when {@code MAP_LOCKED | MAP_ANONYMOUS} is used without {@code CAP_IPC_LOCK} and the process
 * has reached its {@code RLIMIT_MEMLOCK} limit; or when a sealed memfd is re-mapped with
 * incompatible flags.
 *
 * <p>The broad {@code MMAP} selector simultaneously injects {@code EPERM} on all call paths.
 * This creates a comprehensive test of privilege-failure handling: a JVM that runs on a
 * noexec filesystem will fail its JIT code-cache allocation; a database engine that uses
 * locked anonymous memory for its page cache will fail its buffer allocation; and any file
 * that is mapped with {@code MAP_SHARED | PROT_WRITE} on a noexec or read-only filesystem
 * will fail its mapping.
 *
 * <p>glibc's standard {@code malloc} never uses {@code PROT_EXEC} or {@code MAP_LOCKED},
 * so it will not encounter real {@code EPERM} under normal conditions. JVM internal allocators,
 * JNA, and database engines that use mlock or executable mappings are at risk.
 *
 * <p>Compared with {@code EACCES}: {@code EPERM} is a structural check failure (the operation
 * itself is disallowed for this process class); {@code EACCES} is a credential check failure
 * on a specific object. Both are non-transient; neither will succeed on retry without privilege
 * elevation or policy change.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEperm(probability = 0.001)
 * class PrivilegeDenialTest {
 *   @Test
 *   void appHandlesEpermOnAllMmaps(RedisConnectionInfo info) {
 *     // verify the application surfaces a privilege diagnostic and does not retry infinitely
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; privilege failures are rare in
 * well-configured environments — they surface when policy changes are applied.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.EPERM)
public @interface ChaosMmapEperm {

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
   * @ChaosMmapEperm(id = "primary",  probability = 0.001)
   * @ChaosMmapEperm(id = "replica",  probability = 0.01)
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
    ChaosMmapEperm[] value();
  }
}
