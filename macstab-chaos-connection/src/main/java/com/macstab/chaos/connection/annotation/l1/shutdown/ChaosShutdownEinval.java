/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.shutdown;

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
 * Injects {@code EINVAL} into {@code shutdown(2)}, causing the call to return {@code -1} with
 * {@code errno = EINVAL} as if the {@code how} argument was not one of the valid shutdown
 * directions ({@code SHUT_RD}, {@code SHUT_WR}, {@code SHUT_RDWR}) or the socket is not in a
 * state that supports the requested shutdown direction.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SHUTDOWN}, errno = {@code EINVAL})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code shutdown} call; when it fires the interposer returns {@code -1} with {@code errno = EINVAL}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.NET)} on the container definition causes the
 *       extension to upload {@code libchaos-net.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code connect}, {@code accept}, {@code socket},
 *       {@code bind}, {@code listen}, {@code shutdown}, {@code send}, {@code recv}, and
 *       {@code poll} at the dynamic-linker level.
 *   <li>On each intercepted {@code shutdown} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EINVAL}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EINVAL} from {@code shutdown} is a non-retriable programming error in production:
 *       it indicates an invalid shutdown direction argument. However, libraries that wrap
 *       {@code shutdown} may receive it if a race condition causes the socket to be closed before
 *       shutdown is called. Assert that the application does not propagate the error as a fatal
 *       connection failure — the connection itself may still be valid.
 *   <li>Cleanup code that calls {@code shutdown} before {@code close} must tolerate {@code EINVAL}
 *       without aborting the close sequence; assert that {@code close} is still called on the
 *       socket even when {@code shutdown} returns {@code EINVAL}.
 *   <li>Connection pool teardown paths that call {@code shutdown(SHUT_RDWR)} to signal graceful
 *       close should continue to call {@code close} on error; assert that pool shutdown completes
 *       even when individual {@code shutdown} calls fail.
 *   <li>Assert that the application logs the error at a diagnostic level rather than escalating
 *       it as an unrecoverable failure, since {@code EINVAL} from {@code shutdown} during cleanup
 *       is typically benign.
 * </ul>
 *
 * <p>In production, {@code EINVAL} from {@code shutdown} occurs when a shutdown direction value
 * is corrupted by a bug, when a framework attempts to shut down a socket that is in the wrong
 * state for the requested direction, or when a double-shutdown race condition occurs in a
 * multi-threaded connection pool where two threads attempt to shut down the same socket
 * concurrently.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code shutdown(2)} syscall accepts three valid values for the {@code how} argument:
 * {@code SHUT_RD} (0), {@code SHUT_WR} (1), and {@code SHUT_RDWR} (2). If any other value is
 * passed, the kernel returns {@code EINVAL} immediately without modifying the socket state.
 * On Linux, {@code EINVAL} from {@code shutdown} can also occur if the socket's protocol does not
 * support the shutdown operation, though this is uncommon for TCP/IP sockets.
 *
 * <p>The relationship between {@code shutdown} and {@code close} is important: {@code shutdown}
 * selectively disables communication directions on the socket (sending FIN for the write direction,
 * discarding incoming data for the read direction), while {@code close} releases the file descriptor
 * and decrements the socket's reference count. Many applications call {@code shutdown(SHUT_RDWR)}
 * before {@code close} to ensure that the FIN is sent before the socket is freed; if
 * {@code shutdown} returns {@code EINVAL}, the application must still call {@code close} to
 * release the file descriptor.
 *
 * <p>Java's {@code Socket.close()} calls {@code shutdown(SHUT_RDWR)} internally before closing
 * the file descriptor; if {@code shutdown} returns {@code EINVAL}, the JVM catches the error and
 * proceeds to close the file descriptor without propagating the error to the caller. Application
 * code that calls {@code Socket.shutdownInput()} or {@code Socket.shutdownOutput()} explicitly
 * receives a {@code SocketException} with the message "Invalid argument" if the underlying
 * {@code shutdown} syscall returns {@code EINVAL}.
 *
 * <p>Native code and C-based server processes (Redis, memcached, Nginx) call {@code shutdown}
 * directly and must check the return value; robust implementations log the error and proceed with
 * {@code close} rather than treating {@code EINVAL} from {@code shutdown} as fatal.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosShutdownEinval(toxicity = 0.1)
 * class ShutdownEinvalTest {
 *   @Test
 *   void connectionPoolShutdownCompletesEvenWhenShutdownCallFails(ConnectionInfo info) {
 *     // assert that pool teardown proceeds to close() even when shutdown() returns EINVAL
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosShutdownEnotconn
 * @see ChaosShutdownLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosShutdownEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SHUTDOWN, errno = Errno.EINVAL)
public @interface ChaosShutdownEinval {

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
   * @ChaosShutdownEinval(id = "primary",  probability = 0.001)
   * @ChaosShutdownEinval(id = "replica",  probability = 0.01)
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
    ChaosShutdownEinval[] value();
  }
}
