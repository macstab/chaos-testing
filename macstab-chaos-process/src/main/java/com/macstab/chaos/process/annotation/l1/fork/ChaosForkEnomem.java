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
 * Injects {@code ENOMEM} into {@code fork} calls intercepted by libchaos-process, causing the
 * calling code to observe an out-of-memory failure when attempting to create a child process.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code FORK}, errno = {@code ENOMEM}) tuple.
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
 *   <li>When the trial fires, the interposer sets {@code errno = ENOMEM} and returns {@code -1}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 12,
 *       {@code strerror}: "Out of memory"; no child process is created and the calling process
 *       continues in its current state — no cleanup of child resources is required since no child
 *       was allocated.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code fork} returns {@code -1}; {@code errno = ENOMEM} (12); the kernel cannot allocate
 *       the data structures for the child process ({@code task_struct}, {@code mm_struct}, kernel
 *       stack) — assert that the application treats ENOMEM as a potentially persistent failure
 *       that may require backoff longer than the transient EAGAIN case.</li>
 *   <li>Applications using fork for process-isolation (credential isolation, sandboxing, CGI-style
 *       request handling) must handle ENOMEM without losing the request or leaking state — assert
 *       that the failure path returns a clean error to the caller and does not leave partially
 *       initialised resources from the pre-fork phase.</li>
 *   <li>Assert that the application distinguishes fork-ENOMEM from fork-EAGAIN: ENOMEM (12) means
 *       the kernel cannot allocate memory structures for the child (may persist under node memory
 *       pressure, alert the memory-management team); EAGAIN (11) means the pid table or
 *       {@code RLIMIT_NPROC} is exhausted (self-healing when children exit).</li>
 * </ul>
 * Production failure mode: a security-sensitive service forks a child process for each request to
 * isolate credential access; the Kubernetes node is under memory pressure from OOM-protected pods;
 * fork returns ENOMEM; the service's fork failure path does not return a proper error to the caller,
 * leaving the request pending indefinitely — the service appears healthy to its health check but
 * all requests silently queue without completion.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code ENOMEM} from {@code fork} occurs when the kernel fails to allocate the internal data
 * structures required to represent the child process. The primary structures are {@code task_struct}
 * (the process descriptor, several kilobytes), the kernel stack (typically 16 KiB on 64-bit Linux),
 * and {@code mm_struct} (the memory management descriptor). Copy-on-write page table entries are
 * not duplicated immediately — the actual page table duplication is deferred — so ENOMEM from fork
 * is not directly related to the size of the parent's virtual address space.
 *
 * <p>The distinction from EAGAIN is important for retry strategy: ENOMEM indicates that the node's
 * kernel memory (slab allocator, kmalloc pools) cannot satisfy the allocation, which may persist
 * for the duration of a memory pressure event. EAGAIN indicates that the uid's process quota is
 * full, which self-heals when children are reaped. Applications should implement exponential backoff
 * with a limit for ENOMEM and a shorter-timeout retry for EAGAIN.
 *
 * <p>Fork-ENOMEM is more common in container environments than on bare metal because containers
 * share the kernel's slab memory with the host and other containers. A memory-intensive pod on the
 * same node can fragment the slab allocator's free lists, causing ENOMEM for fork even when the
 * container's cgroup memory limit has not been reached. Applications that observe ENOMEM from fork
 * in production must check node-level kernel memory metrics, not just container-level RSS.
 *
 * <p>Unlike exec-ENOMEM (where the failing process remains alive in its current image), fork-ENOMEM
 * means no child was created — the parent is in a clean state with no child resources to clean up.
 * This makes the error-handling code simpler: there is no dirfd to close, no zombie to reap, and
 * no partial child state. The only requirement is that the parent surfaces the error correctly and
 * does not treat the failure as a success.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosForkEnomem(probability = 0.001)
 * class ForkMemoryPressureTest {
 *   @Test
 *   void serviceReturnsCleanErrorOnForkEnomemAndDoesNotLeakResources(ConnectionInfo info) {
 *     // verify fork failure handled; caller receives error; no pending requests; no leaked state
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; fork-ENOMEM is rare in well-provisioned
 * environments but the silent-failure risk for request-isolation services makes coverage valuable.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosForkEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.FORK, errno = ProcessErrno.ENOMEM)
public @interface ChaosForkEnomem {

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
   * @ChaosForkEnomem(id = "primary",  probability = 0.001)
   * @ChaosForkEnomem(id = "replica",  probability = 0.01)
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
    ChaosForkEnomem[] value();
  }
}
