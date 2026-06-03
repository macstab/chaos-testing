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
 * <p>Delays every {@code Runtime.addShutdownHook()} registration by {@link #hangMs()} milliseconds,
 * simulating a JVM that hangs during shutdown-hook registration and causes a delayed, potentially
 * incomplete graceful-shutdown sequence.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code Runtime#addShutdownHook(Thread)} via the JVM chaos agent and injects a delay
 * before the hook is registered. In production, shutdown-hook hangs occur when a hook itself
 * contains a blocking call (network flush, database close) and that resource is unavailable at
 * shutdown time — causing the JVM to wait indefinitely before the process exits.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * A hanging shutdown hook prevents the JVM from exiting cleanly within its pod termination grace
 * period, causing Kubernetes to SIGKILL the process and skipping remaining hooks. Resources
 * protected by those hooks (connections, file locks, audit logs) are not released.
 *
 * <h2>Industry references</h2>
 *
 * <p>Kubernetes graceful termination (§"Termination of Pods") gives each pod a configurable
 * {@code terminationGracePeriodSeconds}. A hanging shutdown hook that exceeds this window causes
 * SIGKILL. Spring Boot's graceful shutdown documentation warns about this exact scenario.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosShutdownHookHang(hangMs = 10000)
 * class ShutdownHookHangTest {
 *   @Test
 *   void gracefulShutdownCompletesWithinGracePeriod(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosShutdownHookHang.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.ShutdownHookHangComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosShutdownHookHang {

  /**
   * Delay injected when {@code Runtime.addShutdownHook()} is called, in milliseconds.
   *
   * @return hang duration in ms; default 10000
   */
  long hangMs() default 10_000L;

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
    CompositeChaosShutdownHookHang[] value();
  }
}
