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
 * Injects {@code EPERM} ("Operation not permitted") into every process-management syscall
 * intercepted by libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn}, {@code
 * pthread_create}, {@code waitpid}, and their variants — simultaneously, gated by {@link
 * #probability}, modelling capability-restriction failures that affect all process lifecycle
 * operations when container security contexts are tightened.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EPERM}) tuple.
 * The {@code WILDCARD} selector intercepts every process-management syscall family simultaneously:
 * fork, execve, execveat, posix_spawn, posix_spawnp, pthread_create, and waitpid. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.
 *   <li>On each intercepted syscall, a Bernoulli trial with probability {@link #probability} runs.
 *   <li>When the trial fires, the interposer sets {@code errno = EPERM} and returns {@code -1} (or
 *       the errno value directly for pthread_create and POSIX spawn functions) before the real
 *       kernel call executes.
 *   <li>The calling code receives: {@code fork()}/{@code execve()} return {@code -1} with {@code
 *       errno = EPERM} (1); {@code pthread_create} returns {@code EPERM} directly; {@code
 *       strerror(EPERM)}: "Operation not permitted".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code pthread_create} returns {@code EPERM} directly when the {@code pthread_attr_t}
 *       requests a real-time scheduling policy (SCHED_FIFO or SCHED_RR) but the process lacks
 *       CAP_SYS_NICE; assert that the application falls back to SCHED_OTHER rather than logging a
 *       fatal error — EPERM from thread scheduling is a capability issue, not a thread-creation
 *       failure; the thread can still be created with the default scheduling policy.
 *   <li>{@code fork()}/{@code posix_spawn()} return {@code -1}/{@code EPERM} when a seccomp policy
 *       or cgroup restriction blocks the clone syscall; assert that the application escalates
 *       immediately rather than retrying — EPERM from clone/fork indicates a permanent security
 *       policy restriction that cannot be resolved by the process itself.
 *   <li>Assert that the application distinguishes EPERM from EACCES: EPERM means the operation is
 *       not permitted for this process regardless of credentials; EACCES means the operation is not
 *       allowed for this security context (MAC policy) — the escalation path differs.
 *   <li>Assert that the application logs the specific capability it attempted to use (CAP_SYS_NICE,
 *       CAP_SYS_ADMIN, etc.) when EPERM fires from thread or process creation operations that
 *       require capabilities.
 * </ul>
 *
 * Production failure mode: a container is redeployed with a more restrictive Kubernetes security
 * context that drops CAP_SYS_NICE; thread pools using real-time scheduling start returning EPERM
 * from pthread_create; the application treats EPERM as a fatal thread-creation error rather than
 * falling back to SCHED_OTHER; the thread pool exhausts and the container stops serving requests.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EPERM} from process-management syscalls has multiple capability-related sources: {@code
 * pthread_create} returns EPERM when the scheduling policy in {@code pthread_attr_t} requires
 * CAP_SYS_NICE (SCHED_FIFO priority {@literal >} 0 or SCHED_RR) and the process lacks it; {@code
 * fork()} returns EPERM under cgroup restrictions or certain clone flag combinations that require
 * CAP_SYS_ADMIN; {@code execve()} returns EPERM for set-uid binaries when the filesystem has nosuid
 * mount option. Unlike EACCES (MAC policy decisions from SELinux/AppArmor), EPERM comes from DAC
 * capability checks performed by the kernel before the MAC layer.
 *
 * <p>The critical fallback for EPERM from pthread_create with real-time scheduling: the application
 * should catch EPERM, log a warning that real-time scheduling is unavailable (and the capability
 * required), and retry with a SCHED_OTHER attribute. This degraded mode produces correct behavior
 * (the thread is created) with lower scheduling priority than desired. Applications that treat
 * EPERM from thread creation as a fatal error lose all thread pool capacity unnecessarily.
 *
 * <p>The wildcard selector fires EPERM across all process-management families. This validates that
 * every process-management path in the application has a correct EPERM handler — either a fallback
 * (for EPERM from scheduling policy) or an escalation (for EPERM from fork/clone restrictions).
 * Applications that handle EPERM with a single catch-all that either always falls back or always
 * escalates will behave incorrectly for one of the two cases.
 *
 * <p>Compared with EACCES (MAC policy enforcement from SELinux/AppArmor): EPERM arises from DAC
 * capability checks and can sometimes be resolved by the application choosing different parameters
 * (e.g., removing the real-time scheduling request from the thread attribute). EACCES from MAC
 * cannot be resolved by the application — it requires a security policy change by an operator. The
 * application's error classification must distinguish these two sources to route to the correct
 * escalation or recovery path.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEperm(probability = 0.002)
 * class CapabilityRestrictionTest {
 *   @Test
 *   void threadPoolFallsBackToSchedOtherOnEpermAndForkPathEscalates(ConnectionInfo info) {
 *     // drive workload triggering pthread_create with RT scheduling and fork;
 *     // assert EPERM from thread create triggers SCHED_OTHER fallback with warning;
 *     // assert EPERM from fork triggers platform escalation; assert EPERM vs EACCES classified correctly
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3; EPERM from thread creation can be handled
 * gracefully via fallback, so moderate rates are acceptable; values above 0.1 will prevent all
 * real-time thread creation if the application does not implement fallback.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EPERM)
public @interface ChaosWildcardEperm {

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
   * @ChaosWildcardEperm(id = "primary",  probability = 0.001)
   * @ChaosWildcardEperm(id = "replica",  probability = 0.01)
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
    ChaosWildcardEperm[] value();
  }
}
