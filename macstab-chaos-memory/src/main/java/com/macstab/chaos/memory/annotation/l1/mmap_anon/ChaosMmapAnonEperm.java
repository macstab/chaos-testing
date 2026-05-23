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
 * Injects {@code EPERM} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the calling code to observe an operation-not-permitted failure from anonymous memory
 * allocation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code EPERM}) tuple.
 * Compile-time safety: this annotation exists only because {@code EPERM} is a defined POSIX result
 * for {@code mmap}; invalid combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EPERM} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 1,
 *       {@code strerror}: "Operation not permitted".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EPERM} (1); the application
 *       should treat this as a non-transient privilege failure, not as a transient OOM.</li>
 *   <li>glibc {@code malloc} propagates {@code NULL}; JVM allocators raise {@code OutOfMemoryError}.
 *       The distinction from {@code EACCES} is lost at the Java level but is preserved in native
 *       logs.</li>
 *   <li>Assert that the application surfaces a privilege-related diagnostic and that it does not
 *       retry indefinitely — {@code EPERM} is permanent without a privilege change.</li>
 * </ul>
 * Production failure mode: Kubernetes Pod Security Admission or a seccomp profile applied to a
 * newly deployed revision can block {@code mmap(PROT_EXEC)} for JIT-compiled runtimes (JVM,
 * V8, LuaJIT), causing {@code EPERM} to appear in the allocator stack immediately on startup.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EPERM} for {@code mmap} when the operation is not permitted regardless
 * of the process's credentials. On Linux, the kernel returns {@code EPERM} for anonymous mappings
 * in two scenarios: when {@code PROT_EXEC} is requested and {@code vm.mmap_min_addr} combined with
 * a Yama LSM restriction blocks low-address executable mappings; and when the process is subject
 * to a {@code RLIMIT_MEMLOCK} limit and attempts to create a locked anonymous mapping with
 * {@code MAP_LOCKED | MAP_ANONYMOUS} without the {@code CAP_IPC_LOCK} capability.
 *
 * <p>In containerised environments {@code EPERM} on anonymous mappings most commonly arises when
 * a JVM or other JIT runtime requests {@code PROT_EXEC} for its code cache on a host where
 * {@code vm.mmap_min_addr} is set to a non-zero value and the container runs without
 * {@code CAP_SYS_ADMIN}. This causes the JVM to fall back to interpreted mode, producing a
 * severe performance regression rather than an outright failure.
 *
 * <p>The distinction between {@code EPERM} and {@code EACCES} is subtle: {@code EACCES} is a
 * DAC/MAC check failure on a specific object (the fd or the backing file); {@code EPERM} is a
 * structural capability check that the process can never satisfy without privilege elevation.
 * Both surface as allocation failure at the libc level. Well-designed error handlers should
 * log the specific errno rather than a generic "allocation failed" message.
 *
 * <p>glibc {@code malloc} never requests {@code MAP_LOCKED} and uses only {@code PROT_READ |
 * PROT_WRITE} for anonymous allocations, so it never triggers {@code EPERM} from the kernel.
 * JVM internal allocations for code cache ({@code PROT_EXEC}) are at risk; Netty's
 * {@code PooledByteBufAllocator} is not ({@code PROT_READ | PROT_WRITE} only).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEperm(probability = 0.001)
 * class PrivilegeFailureTest {
 *   @Test
 *   void appHandlesEpermOnAlloc(RedisConnectionInfo info) {
 *     // verify the application surfaces a privilege error and does not retry indefinitely
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; privilege failures are rare in
 * well-configured environments but extremely hard to reproduce without fault injection.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.EPERM)
public @interface ChaosMmapAnonEperm {

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
   * @ChaosMmapAnonEperm(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEperm(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEperm[] value();
  }
}
