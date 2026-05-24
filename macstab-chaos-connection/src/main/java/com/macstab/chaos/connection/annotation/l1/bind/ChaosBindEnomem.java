/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.bind;

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
 * Injects {@code ENOMEM} into {@code bind(2)}, causing the call to return {@code -1} with {@code
 * errno = ENOMEM} as if the kernel could not allocate the internal routing and socket address
 * structures required to register the local address.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code BIND}, errno = {@code ENOMEM})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted {@code
 * bind} call; when it fires the interposer returns {@code -1} with {@code errno = ENOMEM} without
 * performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code bind} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets {@code errno =
 *       ENOMEM}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Server startup fails if {@code bind} cannot allocate kernel memory for the listening
 *       address; assert that the startup log reports the out-of-memory condition rather than a
 *       generic bind failure, so that operators know to investigate kernel memory pressure.
 *   <li>Applications that retry {@code bind} on {@code ENOMEM} are reasonable — unlike {@code
 *       EINVAL}, {@code ENOMEM} is a transient condition that may resolve as other allocations are
 *       freed; assert that the retry loop has a bounded maximum duration and back-off delay.
 *   <li>Assert that the application emits a memory-pressure alert or metric when it receives {@code
 *       ENOMEM} from a socket operation, since this indicates kernel memory exhaustion rather than
 *       an application-level problem.
 *   <li>Connection pool implementations that create many sockets rapidly during startup must handle
 *       {@code ENOMEM} gracefully by reducing the initial pool size or deferring socket creation.
 * </ul>
 *
 * <p>In production, {@code ENOMEM} from {@code bind} occurs on hosts running under severe memory
 * pressure where the kernel's networking slab caches (sock, inet_bind_bucket, route cache entries)
 * cannot be allocated. This is more common in containerised environments where multiple workloads
 * share the host kernel's memory, and a memory-hungry neighbour container triggers the OOM
 * condition.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's {@code bind} implementation allocates several internal structures to register the
 * local address: an {@code inet_bind_bucket} entry in the bind hash table that tracks which ports
 * are in use, and routing cache entries if the address requires route lookup. On memory-constrained
 * systems where the kernel slab allocator returns NULL, the bind syscall propagates the allocation
 * failure as {@code ENOMEM}.
 *
 * <p>Unlike user-space {@code malloc} failures (which are mapped to Java {@code OutOfMemoryError}),
 * kernel-side {@code ENOMEM} from a socket syscall does not indicate that the JVM heap is full. The
 * kernel and JVM use entirely separate memory allocators; a host can have ample JVM heap while the
 * kernel networking slab is exhausted. Applications that conflate these two forms of OOM and apply
 * the same recovery strategy (e.g., triggering GC on socket ENOMEM) will fail to resolve the actual
 * kernel memory pressure.
 *
 * <p>Java's NIO and {@code ServerSocket} both map {@code ENOMEM} from {@code bind} to a {@code
 * SocketException} with the message "Cannot allocate memory". Application code that uses {@code
 * e.getMessage().contains("memory")} to detect this condition is fragile; the message text varies
 * across glibc versions and JVM implementations. Prefer inspecting the native error code via JNA or
 * a custom native wrapper for production-quality error classification.
 *
 * <p>The Linux kernel exposes networking slab usage through {@code /proc/slabinfo} and via the
 * {@code SOCK_DIAG} netlink interface. Monitoring the {@code sock}, {@code inet_bind_bucket}, and
 * {@code TCP} slab entries allows an operator to confirm that bind failures are caused by slab
 * exhaustion rather than by other kernel memory subsystems.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosBindEnomem(toxicity = 0.001)
 * class BindEnomemTest {
 *   @Test
 *   void serverEmitsMemoryPressureAlertOnBindEnomem(ConnectionInfo info) {
 *     // assert that the server emits a kernel-memory-pressure metric and applies bounded retry
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosBindEaddrinuse
 * @see ChaosBindLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosBindEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.BIND, errno = Errno.ENOMEM)
public @interface ChaosBindEnomem {

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
   * @ChaosBindEnomem(id = "primary",  probability = 0.001)
   * @ChaosBindEnomem(id = "replica",  probability = 0.01)
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
    ChaosBindEnomem[] value();
  }
}
