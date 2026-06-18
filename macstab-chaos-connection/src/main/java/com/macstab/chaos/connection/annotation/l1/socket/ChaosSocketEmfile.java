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
 * Injects {@code EMFILE} into {@code socket(2)}, causing the call to return {@code -1} with {@code
 * errno = EMFILE} as if the calling process has reached its per-process file descriptor limit
 * ({@code RLIMIT_NOFILE}) and the kernel cannot assign a new file descriptor for the socket.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SOCKET}, errno = {@code
 * EMFILE}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code socket} call; when it fires the interposer returns {@code -1} with {@code errno = EMFILE}
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
 *       errno = EMFILE}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EMFILE} from {@code socket} indicates the process's file descriptor table is
 *       exhausted; no new sockets, files, pipes, or other file-descriptor-using resources can be
 *       created until existing ones are closed. Assert that the application does not spin trying to
 *       create new connections — each retry consumes CPU without progress.
 *   <li>Connection pools that create sockets on demand must handle {@code EMFILE} by either waiting
 *       for an existing connection to become available (backpressure) or returning a "pool
 *       exhausted" error to the caller; assert that the pool does not propagate an uncaught
 *       exception that terminates the thread.
 *   <li>Assert that the application emits an "fd limit reached" alert when {@code EMFILE} is
 *       received from {@code socket}, enabling operators to identify whether the process has a file
 *       descriptor leak or simply needs a higher {@code RLIMIT_NOFILE} setting.
 *   <li>Assert that the application's health check endpoint returns a degraded status when it
 *       cannot create new connections due to {@code EMFILE}, so that load balancers can route
 *       traffic away from the affected instance.
 * </ul>
 *
 * <p>In production, {@code EMFILE} from {@code socket} occurs when a connection pool leaks
 * connections (creating them without closing them on error paths), when a burst of concurrent
 * requests causes connection pool expansion to exceed the process's file descriptor limit, and when
 * the container's {@code ulimit -n} is set too low for the expected connection concurrency. The
 * default soft limit is 1024 on many Linux distributions; long-running Java services may exhaust
 * this with large thread pools and connection pools.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Each process maintains a file descriptor table with entries for each open file, socket, pipe,
 * and epoll instance. The kernel limits the size of this table using the {@code RLIMIT_NOFILE}
 * resource limit, which has a soft limit (enforced by the kernel for new allocations) and a hard
 * limit (the maximum to which the soft limit can be raised). When {@code socket()} is called and
 * the lowest-numbered available file descriptor would exceed the soft limit, the kernel returns
 * {@code EMFILE}.
 *
 * <p>The distinction between {@code EMFILE} (per-process limit) and {@code ENFILE} (system-wide
 * limit) is operationally significant: {@code EMFILE} can be resolved by increasing the process's
 * {@code RLIMIT_NOFILE} (via {@code ulimit -n} or container security context), while {@code ENFILE}
 * requires a host-level change to {@code fs.file-max}. Applications should log both the errno and
 * the current limit ({@code /proc/self/limits}) when they receive either error to help operators
 * distinguish the two cases.
 *
 * <p>Java's NIO and networking layers allocate file descriptors invisibly to application code: each
 * {@code Selector} instance (used by Netty, Vert.x, and other NIO frameworks) consumes at least two
 * file descriptors (epoll fd + pipe for wakeup); each {@code SocketChannel} consumes one. A Netty
 * server with 8 I/O threads, 4 boss threads, and 1000 active connections consumes at least 1024
 * file descriptors before any application-layer sockets are created. The JVM itself opens
 * additional file descriptors for class loading, JMX, and GC logging.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSocketEmfile(toxicity = 0.05)
 * class SocketEmfileTest {
 *   @Test
 *   void connectionPoolBackpressuresCallerWhenFileDescriptorLimitIsReached(ConnectionInfo info) {
 *     // assert that the pool blocks or rejects rather than propagating an uncaught exception
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSocketEnfile
 * @see ChaosSocketEnomem
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSocketEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SOCKET, errno = Errno.EMFILE)
public @interface ChaosSocketEmfile {

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
   * @ChaosSocketEmfile(id = "primary",  probability = 0.001)
   * @ChaosSocketEmfile(id = "replica",  probability = 0.01)
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
    ChaosSocketEmfile[] value();
  }
}
