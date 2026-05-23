/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.fork;

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
 * Injects {@code EAGAIN} into {@code fork} calls intercepted by libchaos-process, causing the
 * calling code to observe a resource-temporarily-unavailable failure when attempting to create
 * a child process.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code FORK}, errno = {@code EAGAIN}) tuple.
 * The {@code FORK} selector intercepts {@code fork} calls only, leaving {@code execve},
 * {@code pthread_create}, and all other process syscalls unaffected. Compile-time safety:
 * invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code fork} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code fork} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EAGAIN} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 11,
 *       {@code strerror}: "Resource temporarily unavailable"; no child process is created and
 *       the calling process continues in its current state.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code fork} returns {@code -1}; {@code errno = EAGAIN} (11); the kernel cannot allocate
 *       the process table entry or the process count has reached {@code RLIMIT_NPROC} — assert
 *       that the application retries with backoff rather than treating EAGAIN as a permanent
 *       failure, since EAGAIN from fork is always transient.</li>
 *   <li>Applications that use fork for request isolation (CGI-style process-per-request, credential
 *       isolation via fork+exec) must handle EAGAIN without dropping the request — assert that
 *       the application's fork failure handler either queues the request for retry or returns a
 *       retriable error to the caller rather than silently discarding it.</li>
 *   <li>Assert that the application distinguishes fork-EAGAIN from fork-ENOMEM: EAGAIN (11) means
 *       the process table is full or {@code RLIMIT_NPROC} is exhausted (transient, retry after
 *       backoff); ENOMEM (12) means the kernel cannot allocate memory structures for the child
 *       (may be persistent under memory pressure, requires different handling).</li>
 * </ul>
 * Production failure mode: a credential-isolation service forks a child process to handle each
 * sensitive request in a separate process boundary; a concurrent request burst exhaust the node's
 * {@code RLIMIT_NPROC}; fork returns EAGAIN; the service treats EAGAIN as a hard failure and
 * returns 500 to the client rather than queuing for retry — every request during the burst fails.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EAGAIN} from {@code fork} has two distinct sources in the kernel: {@code RLIMIT_NPROC}
 * (the per-user limit on the number of processes, checked against the user's current process count
 * in the kernel's user namespace) and the kernel's internal {@code max_threads} limit, which is
 * derived from the available memory divided by a minimum thread size. Both limits are transient
 * in practice: the process count falls as children exit, and memory pressure changes over time.
 * Applications that treat fork-EAGAIN as permanent will fail unnecessarily under bursty load.
 *
 * <p>The {@code RLIMIT_NPROC} source is unique to {@code fork} and {@code clone}: it counts all
 * threads owned by the user, not just the calling process's children. In containerised environments,
 * the runtime and all sidecar processes share the same uid, so a burst of container launches by
 * any one component can trigger {@code RLIMIT_NPROC} for all others. Applications that fork for
 * isolation must monitor the per-uid process count and implement load-shedding before the limit
 * is reached.
 *
 * <p>The contrast with {@code pthread_create}-EAGAIN is important: both return EAGAIN but they
 * indicate different resource classes. Fork-EAGAIN indicates process table or uid slot exhaustion,
 * while pthread_create-EAGAIN indicates the thread library's stack allocation failed or the
 * kernel's thread count limit was reached. The recovery action differs: for fork-EAGAIN, wait for
 * child processes to exit; for pthread_create-EAGAIN, reduce the thread pool size or increase stack
 * reuse.
 *
 * <p>The EAGAIN semantics are symmetric across fork and clone: the kernel uses the same limit
 * checks for both. Applications that mix fork and clone (e.g. using {@code clone(SIGCHLD)} for
 * resource-sharing forks and {@code fork()} for isolation forks) will encounter EAGAIN from both
 * paths when the limit is hit — the chaos annotation only intercepts the {@code fork} libc wrapper,
 * not the raw {@code clone} syscall.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosForkEagain(probability = 0.01)
 * class ForkProcessTablePressureTest {
 *   @Test
 *   void serviceRetriesWithBackoffOnForkEagainAndDoesNotDropRequest(ConnectionInfo info) {
 *     // verify fork failure is retried; EAGAIN does not cause request drop; backoff applied
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EAGAIN from fork is transient and
 * the application should retry; any non-zero probability exercises the retry path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosForkEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.FORK, errno = ProcessErrno.EAGAIN)
public @interface ChaosForkEagain {

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
   * @ChaosForkEagain(id = "primary",  probability = 0.001)
   * @ChaosForkEagain(id = "replica",  probability = 0.01)
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
    ChaosForkEagain[] value();
  }
}
