/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessErrnoBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Injects {@code ENOSYS} ("Function not implemented") into every process-management syscall
 * intercepted by libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn},
 * {@code pthread_create}, {@code waitpid} and their variants — simultaneously, gated by
 * {@link #probability}.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 libchaos-process primitive using the {@code WILDCARD} selector, which targets every
 * syscall family intercepted by the library rather than a single one. The {@code ENOSYS} errno
 * means the kernel does not implement the called function at all — the harshest possible process
 * management failure short of a kernel panic. This combination exists on the wildcard selector
 * because containers running on kernels with restricted syscall filters (seccomp, SELinux deny) or
 * on emulation layers (QEMU, WSL1) can return {@code ENOSYS} for any process syscall at any time.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.</li>
 *   <li>On each intercepted syscall (any of the wildcard family), a Bernoulli trial with
 *       probability {@link #probability} runs.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = ENOSYS} and returns {@code -1}
 *       before the real kernel call executes.</li>
 *   <li>The calling code receives the same value it would from a kernel with the syscall blocked
 *       by seccomp: {@code fork()} returns {@code -1}, {@code pthread_create} returns {@code ENOSYS},
 *       {@code execve} returns {@code -1 / ENOSYS}.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code fork()} returns {@code -1} with {@code errno = ENOSYS}; {@code strerror} yields
 *       "Function not implemented".</li>
 *   <li>{@code pthread_create} returns {@code ENOSYS} directly (POSIX thread API returns the
 *       errno as a value rather than setting the global).</li>
 *   <li>{@code execve} / {@code posix_spawn} return {@code -1 / ENOSYS}; child process is never
 *       created.</li>
 *   <li>Application frameworks that spawn threads or child processes should surface error logs or
 *       graceful-degradation behaviour — assert those rather than raw return codes.</li>
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a container deployed on a hardened kernel with a
 * strict seccomp filter blocks {@code fork} / {@code clone} with {@code ENOSYS}; applications that
 * do not check the return value of {@code pthread_create} dereference a null thread handle and
 * crash silently.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ENOSYS} is the kernel's way of saying it does not support the syscall number at all.
 * On Linux this happens most commonly with seccomp-BPF policies that return {@code ENOSYS} rather
 * than {@code EPERM} (to avoid leaking information about whether the syscall exists), with WSL1
 * which does not implement the full Linux syscall table, and with QEMU user-mode emulation of
 * cross-architecture binaries where the host kernel lacks a translated syscall.
 *
 * <p>The wildcard selector is the highest-blast-radius L1 in the process module — it fires across
 * all syscall families simultaneously. Even at low probabilities, a single fired trial blocks the
 * entire process lifecycle: if {@code fork} returns {@code ENOSYS} during a worker-pool resize,
 * the pool silently stays undersized; if {@code pthread_create} fails, a background reaper thread
 * is never started, causing zombie accumulation.
 *
 * <p>Unlike {@code EAGAIN} (retry may succeed) or {@code ENOMEM} (free memory and retry),
 * {@code ENOSYS} is permanent and non-retriable — no amount of resource management will make the
 * syscall available. Applications that distinguish {@code ENOSYS} from other errnos and degrade
 * gracefully are significantly more portable to restricted deployment environments.
 *
 * <p>Compared with the single-selector wildcards ({@code ChaosWildcardEacces}): {@code ENOSYS}
 * simulates a missing kernel feature, while {@code EACCES} simulates a present but forbidden one.
 * Use {@code ENOSYS} to test portability across kernel variants; use {@code EACCES} to test
 * security-policy handling.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEnosys(probability = 0.001)
 * class SeccompPortabilityTest {
 *
 *   @Test
 *   void threadPoolDegradesgracefullyOnEnosys(AppConnectionInfo info) {
 *     // drive requests that trigger thread creation; assert the app returns errors
 *     // rather than crashing when pthread_create returns ENOSYS
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> {@code 1e-4} to {@code 1e-3} — higher values will
 * typically block container startup since the init sequence itself spawns threads.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container; the default
 * empty string applies to every process-chaos-capable container in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 * @see ChaosWildcardEacces
 */
@Repeatable(ChaosWildcardEnosys.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ENOSYS)
public @interface ChaosWildcardEnosys {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-process
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosWildcardEnosys(id = "primary",  probability = 0.001)
   * @ChaosWildcardEnosys(id = "replica",  probability = 0.01)
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
    ChaosWildcardEnosys[] value();
  }
}
