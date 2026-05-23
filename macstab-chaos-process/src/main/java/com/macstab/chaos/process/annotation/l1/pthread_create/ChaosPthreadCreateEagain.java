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
 * Injects {@code EAGAIN} into {@code pthread_create} calls intercepted by libchaos-process, causing
 * the calling code to observe an insufficient-resources failure when attempting to create a new
 * thread.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code PTHREAD_CREATE}, errno = {@code EAGAIN})
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
 *   <li>When the trial fires, the interposer returns {@code EAGAIN} directly (pthread_create
 *       returns the error code, not -1; it does not set errno).</li>
 *   <li>The calling code receives: return value {@code EAGAIN} (11),
 *       {@code strerror(EAGAIN)}: "Resource temporarily unavailable"; the system lacked the
 *       necessary resources to create another thread, or the system-imposed limit on the number
 *       of threads was reached; no thread is created.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code pthread_create} returns {@code EAGAIN}; no thread is created; assert that the
 *       application checks the return value (pthread_create returns the error code, not -1; it
 *       does not set {@code errno}) and applies a retry strategy with exponential backoff — EAGAIN
 *       from pthread_create is transient and self-heals when threads exit.</li>
 *   <li>Applications that use thread pools must handle pthread_create-EAGAIN during pool growth
 *       phases; assert that the pool falls back to a reduced thread count rather than failing all
 *       tasks, and that it retries pool expansion after a delay.</li>
 *   <li>Assert that the application distinguishes pthread_create-EAGAIN (thread count or stack
 *       memory temporarily exhausted) from ENOMEM (permanent stack allocation failure, separate
 *       error code in some NPTL implementations) — retry is appropriate for EAGAIN but the
 *       backoff interval must account for thread stack reclamation time.</li>
 * </ul>
 * Production failure mode: a server receives a burst of concurrent requests and creates a new
 * thread per request; RLIMIT_NPROC or the system's maximum thread count is reached; pthread_create
 * returns EAGAIN; the server treats EAGAIN as a non-retriable error, drops all new requests
 * silently, and does not report the current thread count — operators cannot distinguish thread
 * exhaustion from application logic failures.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EAGAIN} from {@code pthread_create} on Linux originates from two sources: (1) the
 * system-imposed limit on the total number of threads (controlled by
 * {@code /proc/sys/kernel/threads-max} and {@code RLIMIT_NPROC}); (2) insufficient virtual address
 * space or locked memory to allocate a new thread stack (the NPTL implementation allocates thread
 * stacks using {@code mmap} with PROT_READ|PROT_WRITE|PROT_EXEC; if the virtual address space is
 * exhausted, EAGAIN is returned rather than ENOMEM in some kernel versions). The distinction
 * matters for retry strategy: thread-count EAGAIN self-heals when threads exit; address-space
 * EAGAIN may require explicit stack-size reduction in pthread_attr.
 *
 * <p>pthread_create follows the POSIX error-return convention for thread functions: it returns the
 * error code directly (not -1), and it does not set {@code errno}. Code that checks
 * {@code if (ret == -1)} or {@code if (errno == EAGAIN)} after pthread_create silently misses
 * EAGAIN (11). Code that tests {@code if (ret != 0)} is correct. This convention differs from most
 * libc functions and is a common source of bugs in error-handling paths.
 *
 * <p>The distinction between pthread_create-EAGAIN and fork-EAGAIN is operationally important:
 * pthread_create-EAGAIN shares the RLIMIT_NPROC limit with fork (both create new tasks from the
 * kernel's perspective); a burst of forked children can prevent thread creation and vice versa.
 * Applications using both fork and pthread_create must account for this shared resource limit when
 * calibrating retry budgets.
 *
 * <p>Thread stack size affects the EAGAIN threshold: the default NPTL stack size is 8 MB;
 * reducing the stack size via {@code pthread_attr_setstacksize} allows more threads to coexist in
 * the same virtual address space. Applications that receive EAGAIN from pthread_create may be able
 * to retry with a smaller stack size before falling back to a reduced thread count.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPthreadCreateEagain(probability = 0.01)
 * class PthreadCreateResourcePressureTest {
 *   @Test
 *   void threadPoolFallsBackToReducedCountOnEagainAndRetriesExpansion(ConnectionInfo info) {
 *     // verify return value checked (not errno); backoff retry applied; pool reduced not failed;
 *     // thread count reported; EAGAIN distinguished from ENOMEM
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EAGAIN from pthread_create is transient;
 * any non-zero probability exercises the thread-pool fallback path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPthreadCreateEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.PTHREAD_CREATE, errno = ProcessErrno.EAGAIN)
public @interface ChaosPthreadCreateEagain {

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
   * @ChaosPthreadCreateEagain(id = "primary",  probability = 0.001)
   * @ChaosPthreadCreateEagain(id = "replica",  probability = 0.01)
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
    ChaosPthreadCreateEagain[] value();
  }
}
