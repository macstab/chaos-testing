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
 * Injects {@code EBUSY} ("Device or resource busy") into every process-management syscall
 * intercepted by libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn},
 * {@code pthread_create}, {@code waitpid}, and their variants — simultaneously, gated by
 * {@link #probability}, modelling NPTL stack-cache lock contention and resource-busy conditions
 * that can occur transiently during concurrent process lifecycle operations.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EBUSY}) tuple.
 * The {@code WILDCARD} selector intercepts every process-management syscall family simultaneously:
 * fork, execve, execveat, posix_spawn, posix_spawnp, pthread_create, and waitpid. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.</li>
 *   <li>On each intercepted syscall, a Bernoulli trial with probability {@link #probability}
 *       runs.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EBUSY} and returns {@code -1}
 *       (or the errno value directly for pthread_create and POSIX spawn functions) before the
 *       real kernel call executes.</li>
 *   <li>The calling code receives: {@code fork()} returns {@code -1} with {@code errno = EBUSY}
 *       (16); {@code pthread_create} returns {@code EBUSY} directly; {@code strerror(EBUSY)}:
 *       "Device or resource busy".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code pthread_create} returns {@code EBUSY} directly (not {@code -1}); this is a
 *       glibc/NPTL-specific extension caused by stack-cache lock contention — not in the POSIX
 *       spec and not produced by musl libc; assert that the application handles EBUSY from thread
 *       creation as a transient condition and retries with a brief yield rather than treating it
 *       as a non-retryable error.</li>
 *   <li>{@code fork()} returns {@code -1} with {@code errno = EBUSY}; EBUSY from fork is rare in
 *       practice but can occur under cgroup memory pressure or when the process table structure is
 *       momentarily locked; assert that the application applies back-off before retrying.</li>
 *   <li>Assert that the application's catch-all process error handler correctly propagates EBUSY
 *       and does not conflate it with EAGAIN (retryable with back-off) or ENOMEM (needs resource
 *       release) — EBUSY is specifically a lock-contention or resource-busy signal requiring
 *       only a brief yield, not a resource release.</li>
 *   <li>Assert that the application does not enter a spin loop on EBUSY — an unbounded retry
 *       without any yield increases contention and can make the lock-holder take longer to
 *       complete, extending the EBUSY duration.</li>
 * </ul>
 * Production failure mode: a high-throughput thread pool creates and destroys threads rapidly;
 * under heavy concurrency the NPTL stack-cache lock is held by a preempted thread; all concurrent
 * {@code pthread_create} calls return EBUSY; the pool's error handler treats EBUSY as a fatal
 * error and does not retry; the pool shrinks to zero threads; no new threads can be created until
 * the pool is restarted.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EBUSY} from {@code pthread_create} is a glibc/NPTL implementation detail: the NPTL
 * thread-stack cache uses an internal lock to protect its free-list; under rapid concurrent
 * thread creation/destruction the lock can be contended; glibc returns EBUSY from the stack-cache
 * allocation attempt rather than blocking indefinitely. This behavior is glibc-specific — musl
 * libc allocates thread stacks via mmap without a cache and does not produce EBUSY. Tests using
 * WILDCARD EBUSY verify that the application handles this glibc-specific behavior portably.
 *
 * <p>The wildcard selector fires EBUSY across all process-management families. For fork and
 * posix_spawn, EBUSY is uncommon in practice but possible under extreme kernel resource
 * contention. The wildcard variant is valuable for verifying that the application's generic
 * process-error handling correctly propagates EBUSY without special-casing the specific syscall
 * that returned it.
 *
 * <p>EBUSY is a transient condition — the lock or resource will become available quickly (typically
 * sub-millisecond for the NPTL stack-cache lock). The correct application response is a single
 * brief yield or sleep (1–10 ms) followed by one retry, then escalation if the retry also fails.
 * Applications that spin without yielding make the contention worse; applications that refuse to
 * retry lose the thread slot permanently even though it would have become available within
 * milliseconds.
 *
 * <p>Compared with EAGAIN (resource capacity limit reached, requiring back-off and load reduction)
 * and ENOMEM (memory exhausted, requiring resource release or OOM escalation), EBUSY specifically
 * signals transient lock contention or momentary unavailability of a resource that is about to
 * become free. The application's error classification must distinguish these three cases to apply
 * the correct recovery strategy.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEbusy(probability = 0.002)
 * class NptlStackCacheContention {
 *   @Test
 *   void threadPoolYieldsAndRetriesOnEbusyRatherThanShrinking(ConnectionInfo info) {
 *     // drive concurrent thread creation; assert EBUSY triggers yield-and-retry;
 *     // assert pool size stable; assert no EBUSY treated as fatal; assert no spin loop
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3; EBUSY is expected to resolve quickly so
 * moderate probabilities are safe for startup; values above 0.1 may prevent thread pool
 * initialisation if the pool does not retry on EBUSY.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEbusy.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EBUSY)
public @interface ChaosWildcardEbusy {

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
   * @ChaosWildcardEbusy(id = "primary",  probability = 0.001)
   * @ChaosWildcardEbusy(id = "replica",  probability = 0.01)
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
    ChaosWildcardEbusy[] value();
  }
}
