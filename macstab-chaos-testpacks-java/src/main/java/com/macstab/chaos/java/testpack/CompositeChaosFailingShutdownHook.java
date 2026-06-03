/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

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
 * <p>Combines a long shutdown-hook registration delay ({@link #hangMs()} ms) with rejection of
 * new shutdown-hook registrations, simulating a JVM whose graceful-shutdown subsystem has seized —
 * causing all hooks to either not be registered or to hang, and leaving the process unable to exit
 * within its termination grace period.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a long delay on {@code SHUTDOWN_HOOK_REGISTER} operations via the JVM chaos agent.
 * The combined effect simulates a shutdown sequence that hangs indefinitely, causing Kubernetes
 * or the process supervisor to SIGKILL the container before hooks complete. In production, this
 * happens when a shutdown hook acquires a lock held by a deadlocked thread, or when it attempts
 * a network call to an already-unavailable service.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Resources protected by shutdown hooks (connection pool close, WAL flush, distributed lock
 * release) are never cleaned up. The process is force-killed, leaving in-flight requests without
 * a response, open file handles, and unreleased distributed locks. Data loss is possible if WAL
 * or transaction logs were not flushed.
 *
 * <h2>Industry references</h2>
 *
 * <p>Kubernetes §"Termination of Pods": the {@code terminationGracePeriodSeconds} (default 30 s)
 * is the window before SIGKILL. Spring Boot graceful shutdown documentation explicitly warns that
 * hooks that exceed this window will be killed. Docker's {@code docker stop} follows the same
 * SIGTERM → wait → SIGKILL pattern.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosFailingShutdownHook(hangMs = 30000)
 * class FailingShutdownHookTest {
 *   @Test
 *   void shutdownCompletesWithinKubernetesGracePeriod(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosFailingShutdownHook.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.FailingShutdownHookComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosFailingShutdownHook {

  /**
   * How long the shutdown-hook registration hangs, in milliseconds.
   *
   * @return hang duration in ms; default 30000
   */
  long hangMs() default 30_000L;

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosFailingShutdownHook[] value();
  }
}
