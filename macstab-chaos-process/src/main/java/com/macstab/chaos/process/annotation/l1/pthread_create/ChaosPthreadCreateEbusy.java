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
 * Injects {@code EBUSY} into {@code pthread_create} calls intercepted by libchaos-process, causing
 * the calling code to observe a resource-busy failure from NPTL's internal thread stack cache when
 * attempting to create a new thread.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code PTHREAD_CREATE}, errno = {@code EBUSY})
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
 *   <li>When the trial fires, the interposer returns {@code EBUSY} directly (pthread_create
 *       returns the error code, not -1; it does not set errno).</li>
 *   <li>The calling code receives: return value {@code EBUSY} (16),
 *       {@code strerror(EBUSY)}: "Device or resource busy"; NPTL's internal thread stack cache
 *       is locked by a concurrent thread stack reclamation operation; no thread is created.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code pthread_create} returns {@code EBUSY}; no thread is created; assert that the
 *       application checks the return value (pthread_create returns the error code directly, not
 *       -1; it does not set {@code errno}) and treats EBUSY as a transient condition warranting
 *       a brief retry — the stack cache lock contention self-resolves when the concurrent
 *       reclamation completes.</li>
 *   <li>EBUSY from pthread_create is rare in production but can occur under extreme thread churn:
 *       many threads being created and destroyed concurrently cause NPTL's cached stack pool to
 *       be under concurrent access; assert that the application's thread-creation retry path
 *       handles EBUSY distinctly from EAGAIN — both warrant retry but EBUSY resolves faster
 *       (typically sub-millisecond) than EAGAIN (which requires thread stack reclamation).</li>
 *   <li>Assert that the application does not spin-loop on EBUSY without a yield or sleep;
 *       tight spin loops on EBUSY worsen the contention they are waiting for.</li>
 * </ul>
 * Production failure mode: a high-throughput server uses a thread-per-request model with aggressive
 * thread creation and fast request processing; the thread churn rate causes NPTL's stack cache to
 * be under contention; pthread_create returns EBUSY; the server treats EBUSY as a permanent
 * failure and rejects the request with a 500 error rather than retrying the thread creation after
 * a brief yield.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EBUSY} from {@code pthread_create} is specific to the NPTL (Native POSIX Thread Library)
 * implementation in glibc. NPTL maintains a stack cache — a pool of recently-freed thread stacks
 * that can be reused to avoid repeated mmap/munmap cycles. When a thread exits, its stack is
 * returned to the cache under a lock; when pthread_create needs a stack, it checks the cache under
 * the same lock. Under high thread churn, these two operations contend on the cache lock; if
 * pthread_create finds the lock held, it may return EBUSY rather than blocking indefinitely.
 *
 * <p>pthread_create follows the POSIX error-return convention for thread functions: it returns the
 * error code directly (not -1) and does not set {@code errno}. Code that checks
 * {@code if (ret == -1)} or {@code if (errno == EBUSY)} after pthread_create silently misses
 * EBUSY (16). Code that tests {@code if (ret != 0)} is correct. EBUSY is not listed in the POSIX
 * specification for pthread_create; it is a glibc/NPTL extension error that can appear in
 * production when thread churn is high enough to cause cache lock contention.
 *
 * <p>The distinction between EBUSY and EAGAIN from pthread_create is operationally important:
 * EBUSY indicates a transient internal lock contention that resolves in microseconds to
 * milliseconds; EAGAIN indicates that the system lacks resources to create any new thread, which
 * may persist for the lifetime of threads currently running. Retry strategy should be a brief
 * sleep (1–10 ms) for EBUSY and exponential backoff for EAGAIN. Many applications handle only
 * EAGAIN from pthread_create and silently fail on EBUSY by treating it as an unknown error.
 *
 * <p>musl libc does not implement the NPTL stack cache and therefore will not return EBUSY from
 * pthread_create. This annotation is glibc-specific; tests using it should be restricted to
 * glibc-based container images (Debian-slim, Ubuntu, RHEL UBI).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateEbusy(probability = 0.01)
 * class PthreadCreateStackCacheContentionTest {
 *   @Test
 *   void threadPoolRetriesOnEbusyWithBriefYieldAndDoesNotTreatAsPermanentFailure(ConnectionInfo info) {
 *     // verify return value checked (not errno); EBUSY retried with brief sleep;
 *     // EBUSY distinguished from EAGAIN; no spin loop on EBUSY; request not rejected
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EBUSY represents a brief contention
 * window; any non-zero probability exercises the EBUSY retry path which is commonly missing.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPthreadCreateEbusy.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.PTHREAD_CREATE, errno = ProcessErrno.EBUSY)
public @interface ChaosPthreadCreateEbusy {

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
   * @ChaosPthreadCreateEbusy(id = "primary",  probability = 0.001)
   * @ChaosPthreadCreateEbusy(id = "replica",  probability = 0.01)
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
    ChaosPthreadCreateEbusy[] value();
  }
}
