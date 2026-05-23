/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.pthread_create;

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
 * Injects {@code EPERM} into {@code pthread_create} calls intercepted by libchaos-process, causing
 * the calling code to observe an operation-not-permitted failure when attempting to create a new
 * thread with a real-time scheduling policy in a capability-dropped container.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code PTHREAD_CREATE}, errno = {@code EPERM})
 * tuple. The {@code PTHREAD_CREATE} selector intercepts {@code pthread_create} calls only, leaving
 * {@code fork}, {@code posix_spawn}, and all other process syscalls unaffected. Compile-time safety:
 * invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code pthread_create} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code pthread_create} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer returns {@code EPERM} directly (pthread_create
 *       returns the error code, not -1; it does not set errno).</li>
 *   <li>The calling code receives: return value {@code EPERM} (1),
 *       {@code strerror(EPERM)}: "Operation not permitted"; the caller does not have the
 *       privilege ({@code CAP_SYS_NICE}) required to set the requested real-time scheduling
 *       policy in the thread attribute; no thread is created.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code pthread_create} returns {@code EPERM}; no thread is created; assert that the
 *       application treats EPERM as a non-retryable privilege error — the scheduling policy
 *       requested in {@code pthread_attr_t} requires {@code CAP_SYS_NICE} which the container
 *       does not have; neither retry nor backoff is appropriate.</li>
 *   <li>Applications that support configurable thread scheduling policies (SCHED_FIFO or SCHED_RR
 *       for latency-sensitive operations) must handle EPERM gracefully when the container security
 *       policy drops {@code CAP_SYS_NICE} — assert that the application falls back to SCHED_OTHER
 *       and logs a diagnostic that includes the requested policy and the capability requirement.</li>
 *   <li>Assert that the application distinguishes pthread_create-EPERM (privilege, non-retryable,
 *       requires security policy change or fallback) from pthread_create-EAGAIN (resource
 *       exhaustion, transient, retry-appropriate) — treating EPERM as EAGAIN causes indefinite
 *       retry loops that consume resources without any possibility of success.</li>
 * </ul>
 * Production failure mode: a low-latency messaging component configures its I/O threads with
 * SCHED_FIFO via pthread_attr; the Kubernetes pod security policy drops CAP_SYS_NICE; pthread_create
 * returns EPERM; the component treats EPERM as a transient failure, retries the thread creation
 * in a tight loop, logs EPERM thousands of times per second, and never surfaces a diagnostic
 * that identifies the missing capability.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EPERM} from {@code pthread_create} is returned when the {@code pthread_attr_t} specifies
 * a real-time scheduling policy (SCHED_FIFO or SCHED_RR) and the calling thread does not have
 * {@code CAP_SYS_NICE}. On Linux, setting a real-time scheduling policy for a new thread requires
 * the same privilege as {@code sched_setscheduler}: either the effective user is root (uid 0) or
 * the process has {@code CAP_SYS_NICE} in its effective capability set. Standard Kubernetes pod
 * security policies drop all capabilities by default; a container that requires real-time scheduling
 * must explicitly add {@code CAP_SYS_NICE} via the pod's {@code securityContext.capabilities.add}
 * field.
 *
 * <p>pthread_create follows the POSIX error-return convention for thread functions: it returns the
 * error code directly (not -1) and does not set {@code errno}. Code that checks
 * {@code if (ret == -1)} or {@code if (errno == EPERM)} silently misses EPERM (1). Code that
 * tests {@code if (ret != 0)} is correct. The scheduling-policy privilege check happens inside
 * the kernel's {@code sched_fork} path, before the new thread is added to the scheduler; the
 * failure is clean (no thread state to clean up).
 *
 * <p>On some kernel and glibc versions, invalid scheduling policy or priority in pthread_attr
 * returns EINVAL rather than EPERM; the distinction depends on the order of validation: EINVAL
 * is returned for structurally invalid values (policy integer not a valid constant) while EPERM
 * is returned for valid but unprivileged requests (valid SCHED_FIFO but no CAP_SYS_NICE).
 * Applications should handle both EINVAL and EPERM as non-retryable errors from pthread_create.
 *
 * <p>The correct fallback strategy for pthread_create-EPERM in a capability-dropped container is
 * to retry with {@code pthread_attr_setschedpolicy(attr, SCHED_OTHER)} and
 * {@code pthread_attr_setschedparam(attr, &default_param)} — this removes the privilege requirement
 * and allows thread creation to succeed with best-effort scheduling. Applications should log the
 * fallback at WARN level so that operators can identify containers that are silently running with
 * degraded scheduling compared to their configured intent.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateEperm(probability = 0.01)
 * class PthreadCreateRealTimePrivilegeTest {
 *   @Test
 *   void ioThreadFallsBackToSchedOtherOnEpermAndLogsCapabilityRequirement(ConnectionInfo info) {
 *     // verify return value checked (not errno); EPERM treated as non-retryable;
 *     // fallback to SCHED_OTHER applied; CAP_SYS_NICE logged; no retry loop
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EPERM is a non-retryable privilege error;
 * any non-zero probability exercises the scheduling-policy fallback path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPthreadCreateEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.PTHREAD_CREATE, errno = ProcessErrno.EPERM)
public @interface ChaosPthreadCreateEperm {

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
   * @ChaosPthreadCreateEperm(id = "primary",  probability = 0.001)
   * @ChaosPthreadCreateEperm(id = "replica",  probability = 0.01)
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
    ChaosPthreadCreateEperm[] value();
  }
}
