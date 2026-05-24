/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.socket;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Injects {@code ENOMEM} into {@code socket(2)}, causing the call to return {@code -1} with {@code
 * errno = ENOMEM} as if the kernel's memory allocator could not allocate the internal socket data
 * structures ({@code sock}, {@code inet_sock}, protocol-specific buffers) needed to create the
 * socket.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SOCKET}, errno = {@code
 * ENOMEM}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code socket} call; when it fires the interposer returns {@code -1} with {@code errno = ENOMEM}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.NET)} on the container definition causes the
 *       extension to upload {@code libchaos-net.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code connect}, {@code accept}, {@code socket}, {@code
 *       bind}, {@code listen}, {@code shutdown}, {@code send}, {@code recv}, and {@code poll} at
 *       the dynamic-linker level.
 *   <li>On each intercepted {@code socket} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = ENOMEM}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOMEM} from {@code socket} indicates kernel memory exhaustion; unlike {@code
 *       EMFILE} (file descriptor table full) or {@code ENFILE} (system-wide fd limit), {@code
 *       ENOMEM} means the kernel's slab allocator could not satisfy a memory allocation request for
 *       the socket's internal structures. Assert that the application treats this as a transient
 *       resource shortage and backs off before retrying.
 *   <li>Connection pools that receive {@code ENOMEM} when expanding capacity should not immediately
 *       retry socket creation in a tight loop; assert that the pool applies exponential back-off
 *       and reduces its expansion rate to give the kernel time to reclaim memory.
 *   <li>Assert that the application does not conflate kernel-side {@code ENOMEM} with {@code
 *       OutOfMemoryError} from the JVM heap — the JVM heap may be healthy while the kernel's slab
 *       allocator is exhausted, and the remediation paths (GC tuning vs. kernel memory tuning) are
 *       different.
 *   <li>Assert that the application emits a "kernel memory exhausted" metric or alert, enabling
 *       operators to correlate socket creation failures with kernel memory pressure events visible
 *       in {@code /proc/meminfo} and {@code dmesg}.
 * </ul>
 *
 * <p>In production, {@code ENOMEM} from {@code socket} occurs during severe kernel memory pressure
 * events, typically when the container's cgroup memory limit is nearly exhausted and the kernel's
 * slab allocator for network structures ({@code kmem_cache} for {@code sock}, {@code inet_sock})
 * cannot grow. It can also occur during rapid connection establishment bursts where many sockets
 * are created simultaneously, transiently exhausting the slab caches before they can expand.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's {@code socket(2)} implementation calls {@code sock_create()} which calls the
 * protocol family's {@code create} function (e.g., {@code inet_create} for AF_INET). This function
 * allocates a {@code sock} structure using {@code sk_alloc()}, which calls {@code
 * kmem_cache_alloc()} on the protocol-specific slab cache. If the slab allocator returns NULL (due
 * to memory pressure or a cgroup memory limit), {@code sk_alloc} returns NULL and {@code
 * inet_create} propagates {@code ENOMEM} to the caller.
 *
 * <p>The kernel also allocates receive and send socket buffers at socket creation time (the sizes
 * are controlled by {@code net.core.rmem_default} and {@code net.core.wmem_default}); if any of
 * these buffer allocations fail, the kernel returns {@code ENOMEM} and frees the partially
 * allocated socket. This makes {@code ENOMEM} from {@code socket} relatively rare compared to
 * {@code ENOMEM} from {@code send}/{@code recv}, since socket creation buffer allocations are small
 * and typically satisfied before per-packet buffer allocations fail.
 *
 * <p>Java maps {@code ENOMEM} from {@code socket} to a {@code SocketException} with the message
 * "Cannot allocate memory". This is the same message as {@code ENOMEM} from other socket-layer
 * calls; application code should log the operation context (socket creation vs. send/recv) to help
 * distinguish which operation failed. The JVM does not translate kernel {@code ENOMEM} into a JVM
 * {@code OutOfMemoryError} — these are entirely separate error domains.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSocketEnomem(toxicity = 0.01)
 * class SocketEnomemTest {
 *   @Test
 *   void connectionPoolBacksOffWhenKernelCannotAllocateSocket(ConnectionInfo info) {
 *     // assert that the pool applies back-off rather than spinning on ENOMEM
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSocketEmfile
 * @see ChaosSocketEnfile
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSocketEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SOCKET, errno = Errno.ENOMEM)
public @interface ChaosSocketEnomem {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
   */
  double toxicity() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-net
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosSocketEnomem(id = "primary",  probability = 0.001)
   * @ChaosSocketEnomem(id = "replica",  probability = 0.01)
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
    ChaosSocketEnomem[] value();
  }
}
