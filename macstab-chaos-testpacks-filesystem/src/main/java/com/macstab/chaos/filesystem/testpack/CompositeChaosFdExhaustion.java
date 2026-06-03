/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Every {@code open()} call returns {@code EMFILE} — the per-process file descriptor limit has
 * been reached. The fault fires on every matched call ({@code toxicity=1.0}), making file descriptor
 * exhaustion immediate and total: no new files, sockets, pipes, or epoll instances can be opened.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects {@code IoRule.errno(wildcard, OPEN, EMFILE, 1.0)} via libchaos-io using a wildcard
 * path selector. In production, EMFILE occurs when a service leaks file descriptors (missing
 * {@code close()} on error paths), when a JVM with many threads each holding a connection pool
 * approaches the process {@code RLIMIT_NOFILE}, or when Kubernetes cgroups enforce a container-
 * level fd limit via {@code prlimit}.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * The process can no longer open any resource. Network connections fail (no sockets), file
 * writes fail (no file descriptors), even logging may fail. Without explicit EMFILE handling the
 * application typically crashes or hangs. Operator intervention — restarting the process or
 * raising the {@code ulimit -n} — is required to restore service.
 *
 * <h2>Industry references</h2>
 *
 * <p>EMFILE is specified in POSIX.1-2017 {@code open(2)}. The Linux kernel default
 * {@code RLIMIT_NOFILE} for containers is 1 048 576 (since kernel 5.15), but many distributions
 * and container runtimes apply a much lower effective limit. Netflix documented EMFILE-induced
 * Eureka client failures in a 2019 engineering blog post on fd leak detection. The JDK NIO
 * {@code SocketChannel.open()} propagates EMFILE as {@code java.io.IOException: Too many open files}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosFdExhaustion
 * class FdExhaustionTest {
 *
 *   @Test
 *   void connectionPoolHandlesEmfileGracefully(DataSource ds) {
 *     assertThatThrownBy(ds::getConnection)
 *         .hasMessageContaining("Too many open files");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosFdExhaustion.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.FdExhaustionComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosFdExhaustion {

  /**
   * Probability that each {@code open()} returns {@code EMFILE}. {@code 1.0} (the default) makes
   * fd exhaustion total — no new descriptors can be opened.
   */
  double toxicity() default 1.0;

  /**
   * Path prefix on which {@code open()} calls may fail with {@code EMFILE}. Must be an absolute
   * path (start with {@code /}). Defaults to {@code "*"} — wildcard matching every path, which is
   * the correct model for EMFILE (a process-wide limit, not path-specific).
   */
  String path() default "*";

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-io.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosFdExhaustion[] value();
  }
}
