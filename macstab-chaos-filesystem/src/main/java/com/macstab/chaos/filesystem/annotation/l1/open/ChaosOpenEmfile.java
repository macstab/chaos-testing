/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.open;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code EMFILE} into {@code open(2)}, causing the call to return {@code -1} with
 * {@code errno = EMFILE} as if the calling process has reached its per-process file descriptor
 * limit ({@code RLIMIT_NOFILE}) and the kernel cannot assign a new file descriptor for the opened
 * file.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code OPEN}, errno = {@code EMFILE})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code open} call; when it fires the interposer returns {@code -1} with {@code errno = EMFILE}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.IO)} on the container definition causes the
 *       extension to upload {@code libchaos-io.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code open}, {@code read}, {@code write}, {@code close},
 *       {@code fsync}, {@code fdatasync}, {@code truncate}, {@code unlink}, {@code rename}, and
 *       {@code fallocate} at the dynamic-linker level.
 *   <li>On each intercepted {@code open} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EMFILE}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EMFILE} from {@code open} indicates the process's file descriptor table is exhausted;
 *       no new files, sockets, pipes, or other resources can be opened until existing descriptors
 *       are closed. Assert that the application does not spin retrying the open — each retry
 *       consumes CPU without progress.
 *   <li>File-based logging frameworks (Log4j, Logback) that open log files on first write must
 *       handle {@code EMFILE} by falling back to stderr; assert that the logger does not throw an
 *       uncaught exception that terminates the logging thread.
 *   <li>Database or message-queue clients that open WAL files, socket files, or pipe-based IPC
 *       channels must propagate {@code EMFILE} as a resource-exhaustion error to their callers;
 *       assert that the error message indicates fd exhaustion rather than a generic IO failure.
 *   <li>Assert that the application emits an "fd limit reached" alert with the current limit from
 *       {@code /proc/self/limits}, enabling operators to increase the container's {@code ulimit -n}.
 * </ul>
 *
 * <p>In production, {@code EMFILE} from {@code open} occurs when an application leaks file
 * descriptors (opening files without closing them on error paths), when a burst of concurrent
 * requests causes concurrent file opens to exceed the process limit, and when a JVM process
 * accumulates file descriptors through NIO selectors, JMX, and class loading without releasing
 * them, gradually approaching the {@code RLIMIT_NOFILE} soft limit.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel allocates file descriptors from the process's file descriptor table, which has a
 * hard upper bound of {@code RLIMIT_NOFILE}. The soft limit (the enforced one) is typically 1024
 * on unmodified Linux systems; it can be raised up to the hard limit with {@code setrlimit(2)}.
 * When {@code open} is called and the lowest available file descriptor number exceeds the soft
 * limit, the kernel returns {@code EMFILE}.
 *
 * <p>Java processes are particularly vulnerable to fd exhaustion because many JVM subsystems
 * consume file descriptors without making it obvious to the application: each {@code Selector}
 * created by NIO frameworks consumes at least two descriptors (epoll fd + wakeup pipe);
 * class loading opens jar files; JMX uses sockets; GC logging and heap dump tools open files.
 * The actual fd usage of a production JVM process is often 3-5x higher than the number of
 * application-visible sockets and files.
 *
 * <p>Java maps {@code EMFILE} from {@code open} to a {@code FileNotFoundException} with the
 * message "Too many open files". The same message is used by some file-not-found errors on
 * certain JVM versions; application code that distinguishes fd exhaustion from genuine
 * file-not-found conditions must inspect the cause chain rather than the message text.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosOpenEmfile(probability = 0.05)
 * class OpenEmfileTest {
 *   @Test
 *   void fileDescriptorExhaustionIsReportedWithOperableError() {
 *     // assert that the application emits a "fd limit reached" alert rather than an NPE
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosOpenEnfile
 * @see ChaosOpenEacces
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosOpenEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.OPEN, errno = Errno.EMFILE)
public @interface ChaosOpenEmfile {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-io
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosOpenEmfile(id = "primary",  probability = 0.001)
   * @ChaosOpenEmfile(id = "replica",  probability = 0.01)
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
    ChaosOpenEmfile[] value();
  }
}
