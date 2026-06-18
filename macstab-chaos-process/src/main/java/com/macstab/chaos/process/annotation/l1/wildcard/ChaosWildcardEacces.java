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
 * Injects {@code EACCES} ("Permission denied") into every process-management syscall intercepted by
 * libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn}, {@code pthread_create},
 * {@code waitpid} and their variants — simultaneously, gated by {@link #probability}.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 libchaos-process primitive using the {@code WILDCARD} selector, which targets every
 * syscall family intercepted by the library rather than a single one. The {@code EACCES} errno
 * means the kernel found the syscall number in its table but the calling process does not have
 * permission to execute it — the result of a MAC policy, capability restriction, or security
 * context mismatch. This combination exists on the wildcard selector because hardened container
 * runtimes commonly drop process-management capabilities across the board: a container stripped of
 * {@code CAP_SYS_ADMIN} or running under a restrictive SELinux policy may receive {@code EACCES}
 * from any of these syscalls depending on the policy version and the specific operation attempted.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.
 *   <li>On each intercepted syscall (any of the wildcard family: fork, execve, posix_spawn,
 *       pthread_create, waitpid, and variants), a Bernoulli trial with probability {@link
 *       #probability} runs.
 *   <li>When the trial fires, the interposer sets {@code errno = EACCES} and returns {@code -1}
 *       before the real kernel call executes.
 *   <li>The calling code receives the same value it would from a kernel with SELinux or AppArmor
 *       denying the syscall: {@code fork()} returns {@code -1}, {@code pthread_create} returns
 *       {@code EACCES}, {@code execve} returns {@code -1 / EACCES}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code fork()} returns {@code -1} with {@code errno = EACCES}; {@code strerror} yields
 *       "Permission denied". The child process is never created.
 *   <li>{@code pthread_create} returns {@code EACCES} directly (POSIX thread API returns the errno
 *       as a value rather than setting the global). The thread is never started.
 *   <li>{@code execve} / {@code posix_spawn} return {@code -1 / EACCES}; the new executable is
 *       never loaded. Any file descriptor setup before the exec call is not cleaned up by the
 *       kernel — assert that the application handles descriptor leaks correctly.
 *   <li>Application frameworks that spawn threads or child processes must surface error logs or
 *       graceful-degradation behaviour when the spawn is blocked — assert those rather than raw
 *       return codes.
 *   <li>Thread pool implementations that receive {@code EACCES} from {@code pthread_create} must
 *       not enter an infinite retry loop — assert that the pool bounds its retry count and surfaces
 *       an alert when the pool falls below minimum size.
 * </ul>
 *
 * <p><strong>Production failure mode:</strong> a container whose security context is changed (new
 * AppArmor profile applied, SELinux label relabelled, or seccomp profile updated) mid-run may find
 * that process-management calls it relied on are now blocked with {@code EACCES}. Worker pool
 * expansions silently fail; background threads that die are not replaced; health checks that spawn
 * a subprocess return stale data.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EACCES} from process-management syscalls arises from MAC (Mandatory Access Control)
 * policy decisions, not from filesystem permissions. The two most common sources are:
 *
 * <p><strong>SELinux:</strong> the process's security context does not have the {@code fork},
 * {@code execve}, or {@code clone} permission in the active type enforcement policy. SELinux
 * denials are logged to the audit subsystem ({@code /var/log/audit/audit.log}) with an AVC denial
 * record. The denial is permanent for the lifetime of the process's current context — unlike {@code
 * EPERM} which can sometimes be resolved by acquiring capabilities.
 *
 * <p><strong>AppArmor:</strong> the process's confinement profile includes a {@code deny fork} or
 * {@code deny exec} rule. AppArmor denials are logged to {@code /var/log/kern.log} and produce an
 * {@code APPARMOR_DENIED} audit record. A container runtime that applies an AppArmor profile at
 * startup applies it for the entire container lifetime.
 *
 * <p>The wildcard selector fires across all process-management calls simultaneously. Even at low
 * probabilities, a single fired trial can break the entire process lifecycle in unexpected ways: if
 * {@code fork} returns {@code EACCES} during a health-check subprocess spawn, the check returns a
 * stale cached result; if {@code pthread_create} fails during an HTTP connection-pool expansion,
 * the pool silently stays undersized under load.
 *
 * <p>Compared with the single-selector annotations ({@code ChaosForkEacces}): this wildcard variant
 * fires across every family, making it harder for the application to handle via a single error path
 * — each calling site must be tested. Use single-selector variants to verify one specific error
 * path; use {@code WILDCARD} to verify the application is resilient across all of them
 * simultaneously. Compared with {@link ChaosWildcardEnosys}: {@code EACCES} simulates a policy that
 * exists but forbids the operation; {@code ENOSYS} simulates a kernel that does not implement the
 * syscall at all.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEacces(probability = 0.001)
 * class SecurityPolicyTest {
 *
 *   @Test
 *   void threadPoolDegradesgracefullyOnEacces(AppConnectionInfo info) {
 *     // drive requests that trigger thread creation; assert the app returns errors
 *     // rather than crashing when pthread_create returns EACCES
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> {@code 1e-4} to {@code 1e-3} — higher values will
 * typically block container startup since the init sequence itself spawns threads.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container; the default empty
 * string applies to every process-chaos-capable container in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 * @see ChaosWildcardEnosys
 */
@Repeatable(ChaosWildcardEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EACCES)
public @interface ChaosWildcardEacces {

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
   * @ChaosWildcardEacces(id = "primary",  probability = 0.001)
   * @ChaosWildcardEacces(id = "replica",  probability = 0.01)
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
    ChaosWildcardEacces[] value();
  }
}
