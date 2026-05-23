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
 * Injects {@code ENFILE} into {@code socket(2)}, causing the call to return {@code -1} with
 * {@code errno = ENFILE} as if the system-wide open file count has reached the kernel's global
 * limit ({@code fs.file-max}) and no new file descriptors can be allocated by any process on
 * the host.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SOCKET}, errno = {@code ENFILE})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code socket} call; when it fires the interposer returns {@code -1} with {@code errno = ENFILE}
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
 *   <li>On each intercepted {@code socket} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ENFILE}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENFILE} from {@code socket} is a system-wide exhaustion condition that affects all
 *       processes on the host simultaneously; unlike {@code EMFILE} (per-process limit), the
 *       application cannot resolve {@code ENFILE} by closing its own file descriptors. Assert that
 *       the application treats {@code ENFILE} as a temporary, host-wide resource shortage and backs
 *       off rather than failing permanently.
 *   <li>Connection pools that receive {@code ENFILE} cannot grow and must either queue requests
 *       (backpressure) or return an error; assert that the pool does not throw an uncaught exception
 *       or leave the caller hanging indefinitely.
 *   <li>Assert that the application logs a clear "system file descriptor limit reached" message
 *       that distinguishes {@code ENFILE} from {@code EMFILE}, enabling operators to identify that
 *       the host-level {@code fs.file-max} tuning is required rather than a per-process ulimit
 *       change.
 *   <li>Assert that the application's retry strategy for {@code ENFILE} includes a meaningful
 *       delay before retrying, since the system-wide limit will not recover unless other processes
 *       close their file descriptors.
 * </ul>
 *
 * <p>In production, {@code ENFILE} from {@code socket} occurs on heavily loaded Kubernetes nodes
 * where multiple containers collectively exhaust the host's {@code fs.file-max} limit. It is
 * distinct from per-container or per-process limits: the kernel's global file table is shared
 * across all cgroups on the host, and a traffic spike on one tenant can cause {@code ENFILE}
 * for unrelated workloads on the same node.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel maintains a global count of open file descriptions ({@code files_struct} instances)
 * across all processes. When this count reaches {@code /proc/sys/fs/file-max}, any attempt to
 * allocate a new file description — including {@code socket()}, {@code open()}, {@code pipe()}, and
 * {@code dup()} — returns {@code ENFILE}. The error is returned by {@code get_empty_filp()} in the
 * kernel's file descriptor allocation path, before the socket-specific initialization begins.
 *
 * <p>The distinction between {@code EMFILE} and {@code ENFILE}: {@code EMFILE} is checked against
 * the process's {@code RLIMIT_NOFILE} soft limit (visible in {@code /proc/self/limits}), while
 * {@code ENFILE} is checked against the system-wide {@code fs.file-max} limit. Both errors present
 * identically from the application's perspective (cannot create new socket), but require different
 * remediation: {@code EMFILE} → increase the process's ulimit; {@code ENFILE} → increase
 * {@code sysctl fs.file-max} on the host.
 *
 * <p>Java maps {@code ENFILE} from {@code socket} to a {@code SocketException} with the message
 * "Too many open files in system" (glibc's {@code strerror(ENFILE)}). This message is
 * distinguishable from the {@code EMFILE} message "Too many open files" and should be logged with
 * enough context (hostname, file-max value from {@code /proc/sys/fs/file-max}) for operators to
 * act on it. The JVM does not map this to an {@code OutOfMemoryError}; it surfaces as a checked
 * {@code SocketException}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSocketEnfile(toxicity = 0.05)
 * class SocketEnfileTest {
 *   @Test
 *   void applicationBacksOffWhenSystemWideFileDescriptorLimitIsReached(ConnectionInfo info) {
 *     // assert that the application backs off and retries rather than failing immediately
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSocketEmfile
 * @see ChaosSocketEnomem
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSocketEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SOCKET, errno = Errno.ENFILE)
public @interface ChaosSocketEnfile {

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
   * @ChaosSocketEnfile(id = "primary",  probability = 0.001)
   * @ChaosSocketEnfile(id = "replica",  probability = 0.01)
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
    ChaosSocketEnfile[] value();
  }
}
