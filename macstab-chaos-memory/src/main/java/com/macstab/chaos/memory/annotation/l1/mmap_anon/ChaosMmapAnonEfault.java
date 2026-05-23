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
 * Injects {@code EFAULT} into {@code mmap(MAP_ANONYMOUS)} calls intercepted by libchaos-memory,
 * causing the calling code to observe a bad-address failure from anonymous memory allocation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_ANON}, errno = {@code EFAULT}) tuple.
 * Compile-time safety: this annotation exists only because {@code EFAULT} is a defined POSIX result
 * for {@code mmap}; invalid combinations have no annotation class and cannot be expressed.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code mmap(MAP_ANONYMOUS)} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EFAULT} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 14,
 *       {@code strerror}: "Bad address".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EFAULT} (14); callers that do not
 *       check the return value will dereference {@code MAP_FAILED} and receive {@code SIGSEGV}.</li>
 *   <li>glibc {@code malloc} and JVM allocators treat this identically to {@code ENOMEM} —
 *       allocation fails, {@code NULL} or {@code OutOfMemoryError} is propagated.</li>
 *   <li>Assert that the application does not segfault: verify a clean error log or error response
 *       is produced rather than an unexpected process crash.</li>
 * </ul>
 * Production failure mode: JIT-compiled code or native extensions passing a stale or
 * garbage-collected pointer as the {@code addr} hint to {@code mmap} can trigger a real
 * {@code EFAULT} from the kernel — a latent bug that is nearly impossible to reproduce without
 * fault injection.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EFAULT} for {@code mmap} when the {@code addr} argument references an
 * address outside the process's accessible address space. For anonymous mappings with a {@code NULL}
 * hint (the overwhelmingly common case), the kernel never raises {@code EFAULT} — the hint is
 * simply ignored. Real {@code EFAULT} on anonymous mappings occurs only when caller-supplied hints
 * point into kernel-space or into unmapped regions, which indicates a programmer error rather than
 * a resource limit.
 *
 * <p>This annotation exercises the error-recovery path that would be triggered by such a bug in
 * production. Because the path is unreachable under normal operation, it is rarely tested — dormant
 * null-pointer dereferences or missing {@code EFAULT} handlers lurk in many native libraries.
 * Injecting {@code EFAULT} stochastically at low probability reliably surfaces these latent bugs
 * during integration testing without causing persistent test failures.
 *
 * <p>The JVM wraps its internal {@code mmap} calls inside native helper functions that translate
 * all {@code MAP_FAILED} returns into a single {@code OutOfMemoryError} without distinguishing
 * the specific errno. For JVM targets the observable difference from {@code ENOMEM} is therefore
 * only in native/JVM-internal crash logs. For C/C++ targets the distinction is significant:
 * POSIX-correct code should handle {@code EFAULT} as an unrecoverable programmer error, not as a
 * transient resource shortage.
 *
 * <p>Compared with siblings: {@code EFAULT} signals an addressing error (process state is suspect);
 * {@code ENOMEM} signals resource exhaustion (retry after freeing memory may help); {@code EINVAL}
 * signals an argument error (a specific argument violates constraints). {@code EFAULT} is the
 * most severe — if a real {@code EFAULT} occurs, the process may already be in a corrupt state.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonEfault(probability = 0.0001)
 * class NativeCodeResilienceTest {
 *   @Test
 *   void appHandlesEfaultOnAlloc(RedisConnectionInfo info) {
 *     // verify no SIGSEGV and that the application produces a diagnostic error
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> very low rates (1e-5 to 1e-4); {@code EFAULT} at high
 * probability will cause the container process to crash with {@code SIGSEGV} if any code path
 * dereferences {@code MAP_FAILED}.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapAnonEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_ANON, errno = MmapErrno.EFAULT)
public @interface ChaosMmapAnonEfault {

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
   * @ChaosMmapAnonEfault(id = "primary",  probability = 0.001)
   * @ChaosMmapAnonEfault(id = "replica",  probability = 0.01)
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
    ChaosMmapAnonEfault[] value();
  }
}
